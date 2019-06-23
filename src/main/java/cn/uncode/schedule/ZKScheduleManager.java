package cn.uncode.schedule;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Timer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

import cn.uncode.schedule.core.IScheduleDataManager;
import cn.uncode.schedule.core.ScheduleServer;
import cn.uncode.schedule.core.ScheduledMethodRunnable;
import cn.uncode.schedule.core.TaskDefine;
import cn.uncode.schedule.util.ScheduleUtil;
import cn.uncode.schedule.zk.ScheduleDataManager4ZK;
import cn.uncode.schedule.zk.ZKManager;

/**
 * 调度器核心管理
 * 
 * @author juny.ye
 * 
 */
public class ZKScheduleManager extends ThreadPoolTaskScheduler implements ApplicationContextAware {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;

	private static final transient Logger LOGGER = LoggerFactory.getLogger(ZKScheduleManager.class);

	private Map<String, String> zkConfig;
	
	protected ZKManager zkManager;

	private IScheduleDataManager scheduleDataManager;

	/**
	 * 当前调度服务的信息
	 */
	protected ScheduleServer currenScheduleServer;

	/**
	 * 是否启动调度管理，如果只是做系统管理，应该设置为false
	 */
	public boolean start = true;

	/**
	 * 心跳间隔
	 */
	private int timerInterval = 2000;

	/**
	 * 是否注册成功
	 */
	private boolean isScheduleServerRegister = false;

	private static ApplicationContext applicationcontext;
	
	private Map<String, Boolean> isOwnerMap = new ConcurrentHashMap<String, Boolean>();

	private Timer hearBeatTimer;
	private Lock initLock = new ReentrantLock();
	private boolean isStopSchedule = false;
	private Lock registerLock = new ReentrantLock();
	
	private volatile String errorMessage = "No config Zookeeper connect information";
	private InitialThread initialThread;

	public ZKScheduleManager() {
		this.currenScheduleServer = ScheduleServer.createScheduleServer(null);
	}

	public void init() throws Exception {
		Properties properties = new Properties();
		for (Map.Entry<String, String> e : this.zkConfig.entrySet()) {
			properties.put(e.getKey(), e.getValue());
		}
		this.init(properties);
	}

	public void reInit(Properties p) throws Exception {
		if (this.start || this.hearBeatTimer != null) {
			throw new Exception("调度器有任务处理，不能重新初始化");
		}
		this.init(p);
	}

	public void init(Properties p) throws Exception {
		if (this.initialThread != null) {
			this.initialThread.stopThread();
		}
		this.initLock.lock();
		try {
			this.scheduleDataManager = null;
			if (this.zkManager != null) {
				this.zkManager.close();
			}
			this.zkManager = new ZKManager(p);
			this.errorMessage = "Zookeeper connecting ......"
					+ this.zkManager.getConnectStr();
			initialThread = new InitialThread(this);
			initialThread.setName("ScheduleManager-initialThread");
			initialThread.start();
		} finally {
			this.initLock.unlock();
		}
	}

	private void rewriteScheduleInfo() throws Exception {
		registerLock.lock();
		try {
			if (this.isStopSchedule) {
				if (LOGGER.isDebugEnabled()) {
					LOGGER.debug("外部命令终止调度,不在注册调度服务，避免遗留垃圾数据："
							+ currenScheduleServer.getUuid());
				}
				return;
			}
			// 先发送心跳信息
			if (errorMessage != null) {
				this.currenScheduleServer.setDealInfoDesc(errorMessage);
			}
			if (!this.scheduleDataManager
					.refreshScheduleServer(this.currenScheduleServer)) {
				// 更新信息失败，清除内存数据后重新注册
				this.clearMemoInfo();
				this.scheduleDataManager.registerScheduleServer(this.currenScheduleServer);
			}
			isScheduleServerRegister = true;
		} finally {
			registerLock.unlock();
		}
	}

	/**
	 * 清除内存中所有的已经取得的数据和任务队列,在心态更新失败，或者发现注册中心的调度信息被删除
	 */
	public void clearMemoInfo() {
		try {

		} finally {
		}

	}

	/**
	 * 根据当前调度服务器的信息，重新计算分配所有的调度任务
	 * 任务的分配是需要加锁，避免数据分配错误。为了避免数据锁带来的负面作用，通过版本号来达到锁的目的
	 * 
	 * 1、获取任务状态的版本号 2、获取所有的服务器注册信息和任务队列信息 3、清除已经超过心跳周期的服务器注册信息 3、重新计算任务分配
	 * 4、更新任务状态的版本号【乐观锁】 5、根系任务队列的分配信息
	 * 
	 * @throws Exception
	 */
	public void assignScheduleTask() throws Exception {
		scheduleDataManager.clearExpireScheduleServer();
		List<String> serverList = scheduleDataManager.loadScheduleServerNames();
		if (!scheduleDataManager.isLeader(this.currenScheduleServer.getUuid(),
				serverList)) {
			if (LOGGER.isDebugEnabled()) {
				LOGGER.debug(this.currenScheduleServer.getUuid()
						+ ":不是负责任务分配的Leader,直接返回");
			}
			return;
		}
		//黑名单
		List<String> serverIpList = ScheduleUtil.getServerIpList(serverList);
		for(String ip:zkManager.getIpBlacklist()){
			int index = serverIpList.indexOf(ip);
			if (index > -1){
				serverList.remove(index);
			}
		}
		// 设置初始化成功标准，避免在leader转换的时候，新增的线程组初始化失败
		scheduleDataManager.assignTask(this.currenScheduleServer.getUuid(), serverList);
	}

	/**
	 * 1. 定时向数据配置中心更新当前服务器的心跳信息。 如果发现本次更新的时间如果已经超过了，服务器死亡的心跳周期，则不能在向服务器更新信息。
	 * 而应该当作新的服务器，进行重新注册。
	 * 2. 任务分配
	 * 3. 检查任务是否属于本机，是否添加到调度器
	 * 
	 * @throws Exception
	 */
	public void refreshScheduleServer() throws Exception {
		try {
			// 更新或者注册服务器信息
			rewriteScheduleInfo();
			// 如果任务信息没有初始化成功，不做任务相关的处理
			if (!this.isScheduleServerRegister) {
				return;
			}

			// 重新分配任务
			this.assignScheduleTask();
			// 检查本地任务
			this.checkLocalTask();
		} catch (Throwable e) {
			// 清除内存中所有的已经取得的数据和任务队列,避免心跳线程失败时候导致的数据重复
			this.clearMemoInfo();
			if (e instanceof Exception) {
				throw (Exception) e;
			} else {
				throw new Exception(e.getMessage(), e);
			}
		}
	}
	
	public void checkLocalTask() throws Exception {
		// 检查系统任务执行情况
		scheduleDataManager.checkLocalTask(this.currenScheduleServer.getUuid());
	}

	/**
	 * 在Zk状态正常后回调数据初始化
	 * 
	 * @throws Exception
	 */
	public void initialData() throws Exception {
		this.zkManager.initial();
		this.scheduleDataManager = new ScheduleDataManager4ZK(this.zkManager);
		logger.debug("init : " + this.scheduleDataManager);
		if (this.start) {
			// 注册调度管理器
			this.scheduleDataManager.registerScheduleServer(this.currenScheduleServer);
			if (hearBeatTimer == null) {
				hearBeatTimer = new Timer("ScheduleManager-"
						+ this.currenScheduleServer.getUuid() + "-HearBeat");
			}
			hearBeatTimer.schedule(new HeartBeatTimerTask(this), 2000, this.timerInterval);
		}
	}
	
	/**
	 * 将Spring的定时任务进行包装，决定任务是否在本机执行。
	 * @param task
	 * @return
	 */
	private Runnable taskWrapper(final Runnable task){
		return new Runnable(){
			public void run(){
				String taskId = null;
				Long expire = null;
				if(task instanceof ScheduledMethodRunnable){
					ScheduledMethodRunnable uncodeScheduledMethodRunnable = (ScheduledMethodRunnable)task;
					taskId = uncodeScheduledMethodRunnable.getTaskId();
					expire = uncodeScheduledMethodRunnable.getExpire();
				}
		    	if(StringUtils.isNotEmpty(taskId)){
		    		Long now = System.currentTimeMillis();
		    		if(expire != null && expire < now) {
						LOGGER.debug("Job(" + taskId + ") has been expired");
		    			return;
		    		}
		    		String name = taskId;
		    		boolean isOwner = false;
					try {
						if(!isScheduleServerRegister){
							Thread.sleep(1000);
						}
						if(zkManager.checkZookeeperState()){
							isOwner = scheduleDataManager.isOwner(name, currenScheduleServer.getUuid());
							isOwnerMap.put(name, isOwner);
						}else{
							// 如果zk不可用，使用历史数据
							if(null != isOwnerMap){
								isOwner = isOwnerMap.get(name);
							}
						}
						if(isOwner){
			    			task.run();
			    			scheduleDataManager.saveRunningInfo(name, currenScheduleServer.getUuid());
							LOGGER.info("Job(" + taskId + ") has been executed.");
			    		}
					} catch (Exception e) {
						LOGGER.error("Check task owner error.", e);
					}
		    	}
			}
		};
	}

	class HeartBeatTimerTask extends java.util.TimerTask {
		private transient final Logger log = LoggerFactory.getLogger(HeartBeatTimerTask.class);
		ZKScheduleManager manager;

		public HeartBeatTimerTask(ZKScheduleManager aManager) {
			manager = aManager;
		}

		public void run() {
			try {
				Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
				manager.refreshScheduleServer();
			} catch (Exception ex) {
				log.error(ex.getMessage(), ex);
			}
		}
	}

	class InitialThread extends Thread {
		private transient Logger log = LoggerFactory.getLogger(InitialThread.class);
		ZKScheduleManager sm;

		public InitialThread(ZKScheduleManager sm) {
			this.sm = sm;
		}

		boolean isStop = false;

		public void stopThread() {
			this.isStop = true;
		}

		@Override
		public void run() {
			sm.initLock.lock();
			try {
				int count = 0;
				while (!sm.zkManager.checkZookeeperState()) {
					count = count + 1;
					if (count % 50 == 0) {
						sm.errorMessage = "Zookeeper connecting ......"
								+ sm.zkManager.getConnectStr() + " spendTime:"
								+ count * 20 + "(ms)";
						log.error(sm.errorMessage);
					}
					Thread.sleep(20);
					if (this.isStop) {
						return;
					}
				}
				sm.initialData();
			} catch (Throwable e) {
				log.error(e.getMessage(), e);
			} finally {
				sm.initLock.unlock();
			}

		}

	}

	public IScheduleDataManager getScheduleDataManager() {
		return scheduleDataManager;
	}

	@Override
	public void setApplicationContext(ApplicationContext applicationcontext)
			throws BeansException {
		ZKScheduleManager.applicationcontext = applicationcontext;
	}
	
	public void setZkManager(ZKManager zkManager) {
		this.zkManager = zkManager;
	}
	
	public ZKManager getZkManager() {
		return zkManager;
	}

	public void setZkConfig(Map<String, String> zkConfig) {
		this.zkConfig = zkConfig;
	}
	
	private void addTask(Runnable task, TaskDefine taskDefine){
		if(task instanceof org.springframework.scheduling.support.ScheduledMethodRunnable){
			try {
				logger.debug("check: " + this.scheduleDataManager);
				logger.debug("add: " + taskDefine.stringKey());
				scheduleDataManager.addTask(taskDefine);
			} catch (Exception e) {
				LOGGER.error(String.format("add task exception, taskName:%s", taskDefine.stringKey()), e);
			}
		}
	}
	
	public ScheduledFuture<?> schedule(TaskDefine taskDefine, Runnable task) {
		Trigger cronTrigger = new CronTrigger(taskDefine.getCronExpression());
		LOGGER.info("spring task init------trigger:" + taskDefine.getCronExpression());
		addTask(task, taskDefine);
		return super.schedule(taskWrapper(task), cronTrigger);
	}

	public ScheduledFuture<?> scheduleByStartTime(TaskDefine taskDefine,Runnable task) {
		Date startTime = taskDefine.getStartTime();
		LOGGER.info("spring task init------taskName:{}, period:{}", taskDefine.stringKey(), startTime);
		addTask(task, taskDefine);
		logger.debug("start schedule: " + task);
		return super.schedule(taskWrapper(task), startTime);
	}

	public ScheduledFuture<?> scheduleAtFixedRate(TaskDefine taskDefine, Runnable task) {
		long period = taskDefine.getPeriod();
		LOGGER.info("spring task init------taskName:{}, period:{}", taskDefine.stringKey(), period);
		addTask(task, taskDefine);
		Date startTime = taskDefine.getStartTime();
		if(startTime == null) {
			return super.scheduleAtFixedRate(taskWrapper(task), period);
		}
		return super.scheduleAtFixedRate(taskWrapper(task), startTime, period);
	}

	
	public String getScheduleServerUUid(){
		if(null != currenScheduleServer){
			return currenScheduleServer.getUuid();
		}
		return null;
	}

	public Map<String, Boolean> getIsOwnerMap() {
		return isOwnerMap;
	}
	
	public List<String> loadScheduleServerIps() throws Exception{
		return scheduleDataManager.loadScheduleServerIps();
	}

	public static ApplicationContext getApplicationcontext() {
		return ZKScheduleManager.applicationcontext;
	}
	

}