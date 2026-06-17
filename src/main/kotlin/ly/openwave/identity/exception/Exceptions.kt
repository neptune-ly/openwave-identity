package ly.openwave.identity.exception

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException

sealed class RegistryException(val code: String, message: String, val status: HttpStatus) :
    RuntimeException(message)

class IdentityNotFoundException(handle: String) :
    RegistryException("IDENTITY_NOT_FOUND", "No identity found for handle '$handle'", HttpStatus.NOT_FOUND)

class HandleTakenException(handle: String) :
    RegistryException("HANDLE_TAKEN", "The handle '$handle' is already claimed", HttpStatus.CONFLICT)

class HandleInvalidFormatException(handle: String) :
    RegistryException("HANDLE_INVALID_FORMAT", "Handle '$handle' is invalid. Use 3-32 lowercase alphanumeric characters, dots, underscores, or hyphens.", HttpStatus.UNPROCESSABLE_ENTITY)

class AccountAlreadyLinkedException(bankHandle: String) :
    RegistryException("ACCOUNT_ALREADY_LINKED", "A '$bankHandle' account is already linked to this identity", HttpStatus.CONFLICT)

class AccountNotFoundException(bankHandle: String) :
    RegistryException("ACCOUNT_NOT_FOUND", "No linked account found for bank '$bankHandle'", HttpStatus.NOT_FOUND)

class BankNotFoundException(handle: String) :
    RegistryException("BANK_NOT_REGISTERED", "Bank '$handle' is not registered", HttpStatus.NOT_FOUND)

class BankHandleTakenException(handle: String) :
    RegistryException("BANK_HANDLE_TAKEN", "Bank handle '$handle' is already registered", HttpStatus.CONFLICT)

class CustomerEmailRequiredException :
    RegistryException(
        "CUSTOMER_EMAIL_REQUIRED",
        "Customer email is required for digital identity enrollment.",
        HttpStatus.BAD_REQUEST
    )

class ForbiddenException(msg: String = "Forbidden") :
    RegistryException("FORBIDDEN", msg, HttpStatus.FORBIDDEN)

data class ErrorResponse(val code: String, val message: String, val details: Any? = null)

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(RegistryException::class)
    fun handle(ex: RegistryException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(ex.status).body(ErrorResponse(ex.code, ex.message ?: ex.code))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val msg = ex.bindingResult.fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
        return ResponseEntity.badRequest().body(ErrorResponse("VALIDATION_ERROR", msg))
    }

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(ex: ResponseStatusException): ResponseEntity<ErrorResponse> {
        val code = (ex.statusCode as? HttpStatus)?.name ?: "HTTP_${ex.statusCode.value()}"
        val message = ex.reason ?: "Request could not be completed"
        return ResponseEntity.status(ex.statusCode).body(ErrorResponse(code, message))
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ResponseEntity<ErrorResponse> =
        ResponseEntity.internalServerError().body(ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred"))
}
