package ai.govbiz.core.account.helper

/**
 * 새 비밀번호 규칙입니다. 영문 대·소문자, 숫자, 특수문자(공백을 뺀 ASCII)만 8~72자로 받습니다.
 * 국내 서비스 관례대로 한글·이모지·공백은 거부하며, 72자는 BCrypt의 UTF-8 72바이트 한도 안입니다.
 */
object PasswordValidationHelper {
    val CHARACTER_LENGTH = 8..72
    private val ALLOWED_CHARACTERS = Regex("[\\x21-\\x7E]+")

    fun hasAllowedCharacters(password: String): Boolean = ALLOWED_CHARACTERS.matches(password)

    fun requireNewPassword(password: String) {
        require(password.length in CHARACTER_LENGTH && hasAllowedCharacters(password)) {
            "password must be 8..72 ASCII letters, digits or symbols without spaces"
        }
    }
}
