package io.github.koogcompose.observability

// Public API for observability

// Events
public typealias SessionStartedEvent = AgentEvent.SessionStarted
public typealias PhaseTransitionedEvent = AgentEvent.PhaseTransitioned
public typealias ToolCalledEvent = AgentEvent.ToolCalled
public typealias GuardrailDeniedEvent = AgentEvent.GuardrailDenied
public typealias LLMRequestedEvent = AgentEvent.LLMRequested
public typealias AgentStuckEvent = AgentEvent.AgentStuck
public typealias TurnFailedEvent = AgentEvent.TurnFailed

// Sinks
public typealias ObservabilitySink = EventSink
