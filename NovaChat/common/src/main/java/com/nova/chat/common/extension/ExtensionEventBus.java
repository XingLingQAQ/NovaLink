package com.nova.chat.common.extension;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Event bus for extension events.
 * 
 * <p>The event bus allows extensions to register listeners for specific event types
 * and fire events that will be delivered to all registered listeners.
 * 
 * <p>Example usage:
 * <pre>
 * // Register a listener
 * eventBus.register("my-extension", ChatMessageEvent.class, event -> {
 *     System.out.println("Message: " + event.getMessage());
 * });
 * 
 * // Fire an event
 * ChatMessageEvent event = new ChatMessageEvent(player, message);
 * eventBus.fire(event);
 * </pre>
 * 
 * @see ExtensionEvent
 * @see ExtensionEventListener
 */
public class ExtensionEventBus {
    
    private static final Logger LOGGER = Logger.getLogger(ExtensionEventBus.class.getName());
    
    private final Map<Class<? extends ExtensionEvent>, List<RegisteredListener<?>>> listeners;
    private final Map<String, List<ListenerRegistration>> extensionListeners;
    
    /**
     * Internal class to track listener registrations.
     */
    private static class RegisteredListener<T extends ExtensionEvent> {
        final String extensionId;
        final ExtensionEventListener<T> listener;
        final int priority;
        
        RegisteredListener(String extensionId, ExtensionEventListener<T> listener, int priority) {
            this.extensionId = extensionId;
            this.listener = listener;
            this.priority = priority;
        }
    }
    
    /**
     * Internal class to track which listeners belong to which extension.
     */
    private static class ListenerRegistration {
        final Class<? extends ExtensionEvent> eventClass;
        final RegisteredListener<?> listener;
        
        ListenerRegistration(Class<? extends ExtensionEvent> eventClass, RegisteredListener<?> listener) {
            this.eventClass = eventClass;
            this.listener = listener;
        }
    }

    
    /**
     * Event priority levels.
     */
    public static final int PRIORITY_LOWEST = -100;
    public static final int PRIORITY_LOW = -50;
    public static final int PRIORITY_NORMAL = 0;
    public static final int PRIORITY_HIGH = 50;
    public static final int PRIORITY_HIGHEST = 100;
    public static final int PRIORITY_MONITOR = 200;
    
    /**
     * Creates a new ExtensionEventBus.
     */
    public ExtensionEventBus() {
        this.listeners = new ConcurrentHashMap<>();
        this.extensionListeners = new ConcurrentHashMap<>();
    }
    
    /**
     * Registers an event listener with normal priority.
     * 
     * @param extensionId the ID of the extension registering the listener
     * @param eventClass the class of events to listen for
     * @param listener the listener to register
     * @param <T> the event type
     */
    public <T extends ExtensionEvent> void register(String extensionId, 
                                                     Class<T> eventClass, 
                                                     ExtensionEventListener<T> listener) {
        register(extensionId, eventClass, listener, PRIORITY_NORMAL);
    }
    
    /**
     * Registers an event listener with a specific priority.
     * 
     * <p>Listeners with higher priority are called first. If a listener cancels
     * an event, lower priority listeners will still be called but can check
     * the cancelled state.
     * 
     * @param extensionId the ID of the extension registering the listener
     * @param eventClass the class of events to listen for
     * @param listener the listener to register
     * @param priority the listener priority (higher = called first)
     * @param <T> the event type
     */
    public <T extends ExtensionEvent> void register(String extensionId, 
                                                     Class<T> eventClass, 
                                                     ExtensionEventListener<T> listener,
                                                     int priority) {
        Objects.requireNonNull(extensionId, "extensionId cannot be null");
        Objects.requireNonNull(eventClass, "eventClass cannot be null");
        Objects.requireNonNull(listener, "listener cannot be null");
        
        RegisteredListener<T> registered = new RegisteredListener<>(extensionId, listener, priority);
        
        // Add to event type listeners
        listeners.computeIfAbsent(eventClass, k -> new CopyOnWriteArrayList<>())
                 .add(registered);
        
        // Sort by priority (highest first)
        List<RegisteredListener<?>> eventListeners = listeners.get(eventClass);
        eventListeners.sort((a, b) -> Integer.compare(b.priority, a.priority));
        
        // Track for extension cleanup
        extensionListeners.computeIfAbsent(extensionId, k -> new CopyOnWriteArrayList<>())
                          .add(new ListenerRegistration(eventClass, registered));
        
        LOGGER.fine("Registered listener for " + eventClass.getSimpleName() + 
                   " from extension " + extensionId);
    }
    
    /**
     * Fires an event to all registered listeners.
     * 
     * @param event the event to fire
     * @param <T> the event type
     * @return the event (may have been modified by listeners)
     */
    @SuppressWarnings("unchecked")
    public <T extends ExtensionEvent> T fire(T event) {
        Objects.requireNonNull(event, "event cannot be null");
        
        List<RegisteredListener<?>> eventListeners = listeners.get(event.getClass());
        if (eventListeners == null || eventListeners.isEmpty()) {
            return event;
        }
        
        for (RegisteredListener<?> registered : eventListeners) {
            try {
                ((ExtensionEventListener<T>) registered.listener).onEvent(event);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, 
                    "Error in event listener from extension " + registered.extensionId + 
                    " for event " + event.getEventName(), e);
            }
        }
        
        return event;
    }
    
    /**
     * Unregisters all listeners for a specific extension.
     * 
     * @param extensionId the extension ID
     */
    public void unregisterAll(String extensionId) {
        List<ListenerRegistration> registrations = extensionListeners.remove(extensionId);
        if (registrations == null) {
            return;
        }
        
        for (ListenerRegistration reg : registrations) {
            List<RegisteredListener<?>> eventListeners = listeners.get(reg.eventClass);
            if (eventListeners != null) {
                eventListeners.remove(reg.listener);
            }
        }
        
        LOGGER.fine("Unregistered all listeners for extension " + extensionId);
    }
    
    /**
     * Gets the number of registered listeners for an event type.
     * 
     * @param eventClass the event class
     * @return the number of listeners
     */
    public int getListenerCount(Class<? extends ExtensionEvent> eventClass) {
        List<RegisteredListener<?>> eventListeners = listeners.get(eventClass);
        return eventListeners != null ? eventListeners.size() : 0;
    }
    
    /**
     * Gets the total number of registered listeners.
     * 
     * @return the total listener count
     */
    public int getTotalListenerCount() {
        return listeners.values().stream()
                        .mapToInt(List::size)
                        .sum();
    }
    
    /**
     * Checks if any listeners are registered for an event type.
     * 
     * @param eventClass the event class
     * @return true if listeners are registered
     */
    public boolean hasListeners(Class<? extends ExtensionEvent> eventClass) {
        List<RegisteredListener<?>> eventListeners = listeners.get(eventClass);
        return eventListeners != null && !eventListeners.isEmpty();
    }
}
