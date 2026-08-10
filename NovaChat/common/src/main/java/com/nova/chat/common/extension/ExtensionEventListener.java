package com.nova.chat.common.extension;

/**
 * Functional interface for extension event listeners.
 * 
 * <p>Extensions can register listeners to handle specific event types:
 * <pre>
 * eventBus.register(extensionId, ChatMessageEvent.class, event -> {
 *     // Handle the event
 * });
 * </pre>
 * 
 * @param <T> the type of event this listener handles
 * @see ExtensionEventBus
 * @see ExtensionEvent
 */
@FunctionalInterface
public interface ExtensionEventListener<T extends ExtensionEvent> {
    
    /**
     * Called when an event of the registered type is fired.
     * 
     * @param event the event that was fired
     */
    void onEvent(T event);
}
