package cn.uncode.schedule.core;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;

import org.springframework.util.ReflectionUtils;

public class ScheduledMethodRunnable implements Runnable
{

	private final TaskDefine taskDefine;

	private final Object target;

	private final Method method;


	public ScheduledMethodRunnable(Object target, Method method, TaskDefine taskDefine)
	{
		this.target = target;
		this.method = method;
		this.taskDefine = taskDefine;
	}

	public TaskDefine getTaskDefine()
	{
		return taskDefine;
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
		return taskDefine.getParams();
	}
	

	public String getTaskId()
	{
		return taskDefine.getTaskId();
	}
	

	public Long getExpire()
	{
		return taskDefine.getExpire();
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
