/******************************************************************
*
*	CyberUtil for Java
*
*	Copyright (C) Satoshi Konno 2002
*
*	File: ListenerList.java
*
*	Revision;
*
*	12/30/02
*		- first revision.
*
******************************************************************/

package org.cybergarage.util;

import java.util.Vector;

@SuppressWarnings({ "serial", "rawtypes" })
public class ListenerList extends Vector
{
	@SuppressWarnings("unchecked")
	public boolean add(Object obj)
	{
		if (0 <= indexOf(obj))
			return false;
		return super.add(obj);
	}
}

