package com.nova.chat.common.extension;

/**
 * Base class for all extension events.
 * 
 * <p>Extensions can listen for events by registering listeners with the
 * {@link ExtensionEventBus}. Events can be cancelled to prevent default behavior.
 * 
 * @see ExtensionEventBus
 * @see ExtensionEventListener
 */
public abstract class ExtensionEvent {
    
    private boolean cancelled;
    private final boolean cancellable;
    
    /**
     * Creates a new non-cancellable event.
     */
    protected ExtensionEvent() {
        this(false);
    }
    
    /**
     * Creates a new event.
     * 
     * @param cancellable whether this event can be cancelled
     */
    protected ExtensionEvent(boolean cancellable) {
        this.cancellable = cancellable;
        this.cancelled = false;
    }
    
    /**
     * Checks if this event has been cancelled.
     * 
     * @return true if cancelled
     */
    public boolean isCancelled() {
        return cancelled;
    }
    
    /**
     * Sets the cancelled state of this event.
     * 
     * @param cancelled true to cancel the event
     * @throws IllegalStateException if the event is not cancellable
     */
    public void setCancelled(boolean cancelled) {
        if (!cancellable && cancelled) {
            throw new IllegalStateException("This event cannot be cancelled");
        }
        this.cancelled = cancelled;
    }
    
    /**
     * Checks if this event can be cancelled.
     * 
     * @return true if cancellable
     */
    public boolean isCancellable() {
        return cancellable;
    }
    
    /**
     * Gets the name of this event type.
     * 
     * @return the event name
     */
    public String getEventName() {
        return getClass().getSimpleName();
    }
}
