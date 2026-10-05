package android.text
interface Editable
object InputType {
    const val TYPE_CLASS_TEXT = 1
    const val TYPE_CLASS_NUMBER = 2
}
open class SpannableStringBuilder(text: CharSequence = "") : Editable {
    private var value: String = text.toString()
    val length: Int get() = value.length
    fun append(text: CharSequence?): SpannableStringBuilder {
        value += text?.toString() ?: ""
        return this
    }
    override fun toString(): String = value
}
