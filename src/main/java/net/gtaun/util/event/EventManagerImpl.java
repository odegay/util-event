/**
 * Copyright (C) 2011-2012 MK124
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.gtaun.util.event;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

import net.gtaun.util.event.events.EventHandlerAddedEvent;
import net.gtaun.util.event.events.EventHandlerRemovedEvent;

/**
 * Standard implementation class of event manager.
 * 
 * @author MK124
 */
public class EventManagerImpl implements EventManager
{
	private static final ThrowableHandler DEFAULT_THROWABLE_HANDLER = new ThrowableHandler()
	{
		@Override
		public void handleThrowable(Throwable throwable)
		{
			if(throwable instanceof AssertionError) throw (AssertionError) throwable;
			throwable.printStackTrace();
		}
	};
	
	private static final Comparator<HandlerEntry> HANDLER_ENTRY_PRIORITY_COMPARATOR = new Comparator<HandlerEntry>()
	{
		@Override
		public int compare(HandlerEntry o1, HandlerEntry o2)
		{
			return o2.getPriority() - o1.getPriority();
		}
	};
	
	public class HandlerEntryImpl implements HandlerEntry
	{
		private Class<? extends Event> type;
		private Object relatedObject;
		private EventHandler handler;
		private short priority;
		
		private boolean isCanceled = false;
		
		
		public HandlerEntryImpl(Class<? extends Event> type, Object relatedObject, EventHandler handler, short priority)
		{
			this.type = type;
			this.relatedObject = relatedObject;
			this.handler = handler;
			this.priority = priority;
		}
		
		@Override
		protected void finalize() throws Throwable
		{
			super.finalize();
			
			if(isCanceled) return;
			cancel();

			EventHandlerRemovedEvent event = new EventHandlerRemovedEvent(this);
			dispatchEvent(event, this);
		}
		
		@Override
		public String toString()
		{
			return ToStringBuilder.reflectionToString(this, ToStringStyle.DEFAULT_STYLE);
		}
		
		@Override
		public EventManager getEventManager()
		{
			return EventManagerImpl.this;
		}
		
		@Override
		public void cancel()
		{
			removeHandler(this);
			isCanceled = true;
		}

		@Override
		public Class<? extends Event> getType()
		{
			return type;
		}

		@Override
		public Object getRelatedObject()
		{
			return relatedObject;
		}

		@Override
		public Class<?> getRelatedClass()
		{
			if (relatedObject instanceof Class) return (Class<?>) relatedObject;
			return null;
		}

		@Override
		public EventHandler getHandler()
		{
			return handler;
		}

		@Override
		public short getPriority()
		{
			return priority;
		}
	}
	
	
	private Map<Class<? extends Event>, Map<Object, Queue<Reference<HandlerEntry>>>> handlerEntryContainersMap;
	
	
	public EventManagerImpl()
	{
		handlerEntryContainersMap = new ConcurrentHashMap<>();
	}
	
	@Override
	public HandlerEntry addHandler(Class<? extends Event> type, EventHandler handler, HandlerPriority priority)
	{
		return addHandler(type, Object.class, handler, priority.getValue());
	}
	
	@Override
	public HandlerEntry addHandler(Class<? extends Event> type, EventHandler handler, short priority)
	{
		return addHandler(type, Object.class, handler, priority);
	}
	
	@Override
	public HandlerEntry addHandler(Class<? extends Event> type, Class<?> relatedClass, EventHandler handler, HandlerPriority priority)
	{
		return addHandler(type, (Object) relatedClass, handler, priority.getValue());
	}
	
	@Override
	public HandlerEntry addHandler(Class<? extends Event> type, Class<?> relatedClass, EventHandler handler, short customPriority)
	{
		return addHandler(type, (Object) relatedClass, handler, customPriority);
	}
	
	@Override
	public HandlerEntry addHandler(Class<? extends Event> type, Object relatedObject, EventHandler handler, HandlerPriority priority)
	{
		return addHandler(type, relatedObject, handler, priority.getValue());
	}
	
	@Override
	public HandlerEntry addHandler(Class<? extends Event> type, Object relatedObject, EventHandler handler, short customPriority)
	{
		HandlerEntry entry = new HandlerEntryImpl(type, relatedObject, handler, customPriority);
		return addHandlerEntry(entry);
	}
	
	private HandlerEntry addHandlerEntry(HandlerEntry entry)
	{
		Class<? extends Event> type = entry.getType();
		Object relatedObject = entry.getRelatedObject();
		
		Map<Object, Queue<Reference<HandlerEntry>>> objectEntriesMap = handlerEntryContainersMap.get(type);
		if (objectEntriesMap == null)
		{
			objectEntriesMap = new ConcurrentHashMap<Object, Queue<Reference<HandlerEntry>>>();
			handlerEntryContainersMap.put(type, objectEntriesMap);
		}
		
		Queue<Reference<HandlerEntry>> entries = objectEntriesMap.get(relatedObject);
		if (entries == null)
		{
			entries = new ConcurrentLinkedQueue<Reference<HandlerEntry>>();
			objectEntriesMap.put(relatedObject, entries);
		}
		
		entries.add(new WeakReference<HandlerEntry>(entry));
		
		EventHandlerAddedEvent event = new EventHandlerAddedEvent(entry);
		dispatchEvent(event, this);
		
		return entry;
	}
	
	private void removeHandler(HandlerEntry entry)
	{
		if (entry == null) return;
		
		Class<? extends Event> type = entry.getType();
		Object relatedObject = entry.getRelatedObject();
		
		Map<Object, Queue<Reference<HandlerEntry>>> objectEntriesMap = handlerEntryContainersMap.get(type);
		if (objectEntriesMap == null) return;
		
		Queue<Reference<HandlerEntry>> entries = objectEntriesMap.get(relatedObject);
		if (entries == null) return;
		
		Iterator<Reference<HandlerEntry>> iterator = entries.iterator();
		while(iterator.hasNext())
		{
			Reference<HandlerEntry> ref = iterator.next();
			HandlerEntry e = ref.get();
			
			if (e == null)
			{
				iterator.remove();
				continue;
			}
			
			if (e != entry ) continue;
			iterator.remove();
			
			EventHandlerRemovedEvent event = new EventHandlerRemovedEvent(entry);
			dispatchEvent(event, this);
		}
		
		if (entries.size() == 0) objectEntriesMap.remove(relatedObject);
		if (objectEntriesMap.size() == 0) handlerEntryContainersMap.remove(type);
	}
	
	@Override
	public boolean hasHandler(Class<? extends Event> type, EventHandler handler)
	{
		return hasHandler(type, Object.class, handler);
	}
	
	@Override
	public boolean hasHandler(Class<? extends Event> type, Class<?> clz)
	{
		return hasHandler(type, (Object) clz);
	}
	
	@Override
	public boolean hasHandler(Class<? extends Event> type, Class<?> clz, EventHandler handler)
	{
		return hasHandler(type, (Object) clz, handler);
	}
	
	@Override
	public boolean hasHandler(Class<? extends Event> type, Object object)
	{
		Map<Object, Queue<Reference<HandlerEntry>>> objectEntriesMap = handlerEntryContainersMap.get(type);
		if (objectEntriesMap == null) return false;
		
		Queue<Reference<HandlerEntry>> entries = objectEntriesMap.get(object);
		if (entries == null) return false;
		
		return true;
	}
	
	@Override
	public boolean hasHandler(Class<? extends Event> type, Object object, EventHandler handler)
	{
		Map<Object, Queue<Reference<HandlerEntry>>> objectEntriesMap = handlerEntryContainersMap.get(type);
		if (objectEntriesMap == null) return false;
		
		Queue<Reference<HandlerEntry>> entries = objectEntriesMap.get(object);
		if (entries == null) return false;
		
		for (Reference<HandlerEntry> ref : entries)
		{
			if (ref.get().getHandler() == handler) return true;
		}
		
		return false;
	}
	
	@Override
	public <T extends Event> void dispatchEvent(T event, Object... objects)
	{
		dispatchEvent(null, event, objects);
	}
	
	@Override
	public <T extends Event> void dispatchEvent(ThrowableHandler throwableHandler, T event, Object... objects)
	{
		if (throwableHandler == null) throwableHandler = DEFAULT_THROWABLE_HANDLER;
		if (objects.length == 0) objects = new Object[] { new Object() };
		
		Class<? extends Event> type = event.getClass();
		PriorityQueue<HandlerEntry> handlerEntryQueue = new PriorityQueue<HandlerEntry>(16, HANDLER_ENTRY_PRIORITY_COMPARATOR);
		
		Map<Object, Queue<Reference<HandlerEntry>>> objectEntriesMap = handlerEntryContainersMap.get(type);
		if (objectEntriesMap == null) return;
		
		for (Object object : objects)
		{
			Class<?> cls = object.getClass();
			
			Queue<Reference<HandlerEntry>> entries = objectEntriesMap.get(object);
			if (entries != null)
			{
				for (Reference<HandlerEntry> ref : entries)
				{
					HandlerEntry entry = ref.get();
					if (entry == null) continue;
					else handlerEntryQueue.add(entry);
				}
			}
			
			Class<?>[] interfaces = cls.getInterfaces();
			for (Class<?> clz : interfaces)
			{
				Queue<Reference<HandlerEntry>> classEntries = objectEntriesMap.get(clz);
				if (classEntries != null)
				{
					for (Reference<HandlerEntry> ref : classEntries)
					{
						HandlerEntry entry = ref.get();
						if (entry == null) continue;
						else handlerEntryQueue.add(entry);
					}
				}
			}
			
			for (Class<?> clz = cls; clz != null; clz = clz.getSuperclass())
			{
				Queue<Reference<HandlerEntry>> classEntries = objectEntriesMap.get(clz);
				if (classEntries != null)
				{
					for (Reference<HandlerEntry> ref : classEntries)
					{
						HandlerEntry entry = ref.get();
						if (entry == null) continue;
						else handlerEntryQueue.add(entry);
					}
				}
			}
		}
		
		while (handlerEntryQueue.isEmpty() == false && event.isInterrupted() == false)
		{
			HandlerEntry entry = handlerEntryQueue.poll();
			EventHandler handler = entry.getHandler();
			
			if (handler == null) continue;
			
			try
			{
				handler.handleEvent(event);
			}
			catch (Throwable e)
			{
				throwableHandler.handleThrowable(e);
			}
		}
	}
}
