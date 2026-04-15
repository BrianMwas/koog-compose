package io.github.koogcompose.sample

/**
 * Custom exceptions for exhaustive error handling in robust teaching app.
 * Each exception maps to a specific user-friendly message in errorPresentation.
 */

// Network/Connectivity errors — transient, will retry automatically
class NetworkException(message: String, cause: Throwable? = null) : 
    Exception(message, cause)

// Storage errors — user action needed  
class StorageFullException(message: String, cause: Throwable? = null) : 
    Exception(message, cause)

// Permission errors — requires user settings change
class PermissionDeniedException(message: String, cause: Throwable? = null) : 
    Exception(message, cause)

// Timeout errors — transient, safe to retry
class TimeoutException(message: String, cause: Throwable? = null) : 
    Exception(message, cause)

// Authentication/API errors — often temporary
class AuthenticationException(message: String, cause: Throwable? = null) : 
    Exception(message, cause)

// Server/API unavailable — definitely transient
class ServiceUnavailableException(message: String, cause: Throwable? = null) : 
    Exception(message, cause)

// Rate limiting — transient but needs backoff
class RateLimitException(message: String, cause: Throwable? = null) : 
    Exception(message, cause)

// Data validation/malformed response — typically permanent
class DataValidationException(message: String, cause: Throwable? = null) : 
    Exception(message, cause)

// Corrupted state — user should try again from scratch
class CorruptedStateException(message: String, cause: Throwable? = null) : 
    Exception(message, cause)

// Configuration/Setup errors — needs user intervention
class ConfigurationException(message: String, cause: Throwable? = null) : 
    Exception(message, cause)

// Device errors — can't proceed
class DeviceException(message: String, cause: Throwable? = null) : 
    Exception(message, cause)
