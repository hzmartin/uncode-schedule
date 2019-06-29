package cn.uncode.schedule.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class GsonBox
{

	public static final Gson BASE = newGsonBuilder().create();

	public static GsonBuilder newGsonBuilder()
	{
		return new GsonBuilder();
	}
}
