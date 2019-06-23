package cn.uncode.schedule.core;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;

import org.springframework.util.ReflectionUtils;

public class ScheduledMethodRunnable implements Runnable
{

	private final String taskId;

	private final Object target;

	private final Method method;

	private final String params;
	
	private final Long expire;

	public ScheduledMethodRunnable(Object target, Method method, String params, String taskId, Long expire)
	{
		this.target = target;
		this.method = method;
		this.params = params;
		this.taskId = taskId;
		this.expire = expire;
	}

	public Object getTarget()
	{
		return this.target;
	}

	public Method getMethod()
	{
		return this.method;
	}

	public String getParams()
	{
		return params;
	}
	

	public String getTaskId()
	{
		return taskId;
	}
	

	public Long getExpire()
	{
		return expire;
	}

	@Override
	public void run()
	{
		try
		{
			ReflectionUtils.makeAccessible(this.method);
			if (this.getParams() != null)
			{
				this.method.invoke(this.target, this.getParams());
			}
			else
			{
				this.method.invoke(this.target);
			}
		}
		catch (InvocationTargetException ex)
		{
			ReflectionUtils.rethrowRuntimeException(ex.getTargetException());
		}
		catch (IllegalAccessException ex)
		{
			throw new UndeclaredThrowableException(ex);
		}
	}

}
