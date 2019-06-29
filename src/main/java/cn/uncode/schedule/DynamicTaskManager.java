package cn.uncode.schedule;

import java.lang.reflect.Method;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.util.Assert;
import org.springframework.util.ReflectionUtils;

import cn.uncode.schedule.core.ScheduledMethodRunnable;
import cn.uncode.schedule.core.TaskDefine;



public class DynamicTaskManager {
	
	private static final transient Logger LOGGER = LoggerFactory.getLogger(DynamicTaskManager.class);
	
	
	private static final Map<String, ScheduledFuture<?>> SCHEDULE_FUTURES = new ConcurrentHashMap<String, ScheduledFuture<?>>();
	
	
	public static void clearLocalTask(List<String> existsTaskName){
		for(String name:SCHEDULE_FUTURES.keySet()){
			if(!existsTaskName.contains(name)){
				SCHEDULE_FUTURES.get(name).cancel(true);
				SCHEDULE_FUTURES.remove(name);
			}
		}
	}

	/**
	 * 启动定时任务
	 * 支持：
	 * 1 cron时间表达式，立即执行
	 * 2 startTime + period,指定时间，定时进行
	 * 3 period，定时进行，立即开始
	 * 4 startTime，指定时间执行
	 * 
	 * @param targetBean
	 * @param targetMethod
	 * @param cronExpression
	 * @param startTime
	 * @param period
	 */
	public static void scheduleTask(TaskDefine taskDefine){
		String taskId = taskDefine.getTaskId();
		String targetBean = taskDefine.getTargetBean();
		String targetMethod = taskDefine.getTargetMethod();
		String cronExpression = taskDefine.getCronExpression();
		Date startTime = taskDefine.getStartTime();
		long period = taskDefine.getPeriod();
		try {
			ScheduledFuture<?> scheduledFuture = null;
			ScheduledMethodRunnable scheduledMethodRunnable = buildScheduledRunnable(taskDefine);
			if(scheduledMethodRunnable != null){
				if (!SCHEDULE_FUTURES.containsKey(taskId)) {
					if(StringUtils.isNotEmpty(cronExpression)){
						scheduledFuture = ConsoleManager.getScheduleManager().scheduleCron(taskDefine, scheduledMethodRunnable);
					}else if(startTime != null){
						if(period > 0){
							scheduledFuture = ConsoleManager.getScheduleManager().scheduleAtFixedRate(taskDefine, scheduledMethodRunnable);
						}else{
							scheduledFuture = ConsoleManager.getScheduleManager().scheduleByStartTime(taskDefine, scheduledMethodRunnable);
						}
					}else if(period > 0){
						scheduledFuture = ConsoleManager.getScheduleManager().scheduleAtFixedRate(taskDefine, scheduledMethodRunnable);
					}
					SCHEDULE_FUTURES.put(taskId, scheduledFuture);
					LOGGER.debug("Building new schedule task(" + taskId + "), target bean " + targetBean
							+ " target method " + targetMethod + ".");
				}
			}else{
				LOGGER.debug("Bean name is not exists.");
			}
		} catch (Exception e) {
			LOGGER.error(e.getMessage(), e);
		}
	}
	
	
	/**
	 * 封装任务对象
	 * @param targetBean
	 * @param targetMethod
	 * @param taskId 
	 * @param expire 
	 * @return
	 */
	private static ScheduledMethodRunnable buildScheduledRunnable(TaskDefine taskDefine){

		String targetBean = taskDefine.getTargetBean();
		Object bean;
		ScheduledMethodRunnable scheduledMethodRunnable = null;
		try {
			bean = ZKScheduleManager.getApplicationcontext().getBean(targetBean);
			scheduledMethodRunnable = _buildScheduledRunnable(bean, taskDefine);
		} catch (Exception e) {
			LOGGER.debug(e.getLocalizedMessage(), e);
		}
		return scheduledMethodRunnable;
	}


	private static ScheduledMethodRunnable _buildScheduledRunnable(Object bean, TaskDefine taskDefine) throws Exception {

		String targetMethod = taskDefine.getTargetMethod();
		String params = taskDefine.getParams();
		
		Assert.notNull(bean, "target object must not be null");
		Assert.hasLength(targetMethod, "Method name must not be empty");

		Method method;
		ScheduledMethodRunnable scheduledMethodRunnable;

		Class<?> clazz;
		if (AopUtils.isAopProxy(bean)) {
			clazz = AopProxyUtils.ultimateTargetClass(bean);
		} else {
			clazz = bean.getClass();
		}
		if (params != null) {
			method = ReflectionUtils.findMethod(clazz, targetMethod, String.class);
		} else {
			method = ReflectionUtils.findMethod(clazz, targetMethod);
		}

		Assert.notNull(method, "can not find method named " + targetMethod);
		scheduledMethodRunnable = new ScheduledMethodRunnable(bean, method, taskDefine);
		return scheduledMethodRunnable;
	}
}
