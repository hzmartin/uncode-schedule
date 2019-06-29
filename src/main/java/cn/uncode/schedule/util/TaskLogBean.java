package cn.uncode.schedule.util;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TaskLogBean
{

	private static final Logger logger = LoggerFactory.getLogger(TaskLogBean.class);

	private Runnable task;

	private long begintime = 0;

	private long spendtime = 0;

	private Object params;

	private Map<String, Object> props = new HashMap<String, Object>();

	private Object result;

	private String msg;


	public long getBegintime()
	{
		return begintime;
	}

	public void setBegintime(long begintime)
	{
		this.begintime = begintime;
	}

	public long getSpendtime()
	{
		return spendtime;
	}

	public void setSpendtime(long spendtime)
	{
		this.spendtime = spendtime;
	}

	public Object getParams()
	{
		return params;
	}

	public void setParams(Object params)
	{
		this.params = params;
	}

	public Map<String, Object> getProps()
	{
		return props;
	}

	public void setProps(Map<String, Object> props)
	{
		this.props = props;
	}

	public Object getResult()
	{
		return result;
	}

	public void setResult(Object result)
	{
		this.result = result;
	}

	public TaskLogBean addProp(String key, Object value)
	{
		if (this.getProps() != null)
		{
			this.getProps().put(key, (value == null ? "null" : value));
		}
		return this;
	}

	public static void prop(String key, Object value)
	{
		get().props.put(key, value);
	}

	public Object delProp(String key)
	{
		if (this.getProps() != null)
		{
			return this.getProps().remove(key);
		}
		return null;
	}

	public Object getProp(String key)
	{
		if (this.getProps() != null)
		{
			return this.getProps().get(key);
		}
		return null;
	}

	public void log()
	{
		logger.info(GsonBox.BASE.toJson(this));
	}

	public void print()
	{
		this.print(System.currentTimeMillis());
	}

	public void print(long now)
	{
		this.setSpendtime(now - this.getBegintime());
		this.log();
	}

	private static final ThreadLocal<TaskLogBean> LOG_BEAN_THREAD_LOCAL = new ThreadLocal<TaskLogBean>();

	public static TaskLogBean get()
	{
		TaskLogBean logBean = LOG_BEAN_THREAD_LOCAL.get();
		if (logBean == null)
		{
			logBean = new TaskLogBean();
			LOG_BEAN_THREAD_LOCAL.set(logBean);
		}
		return logBean;
	}

	public static void remove()
	{
		LOG_BEAN_THREAD_LOCAL.remove();
	}

	public static TaskLogBean start()
	{
		TaskLogBean logBean = get();
		logBean.setBegintime(System.currentTimeMillis());
		return logBean;
	}

	public static void end()
	{
		get().print();
		remove();
	}

	public String getMsg()
	{
		return msg;
	}

	public void setMsg(String msg)
	{
		this.msg = msg;
	}

	public Runnable getTask()
	{
		return task;
	}

	public void setTask(Runnable task)
	{
		this.task = task;
	}

}
