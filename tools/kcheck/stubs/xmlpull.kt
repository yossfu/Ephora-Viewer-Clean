package org.xmlpull.v1
import java.io.Reader
interface XmlPullParser {
    fun setFeature(name: String, state: Boolean)
    fun setInput(reader: Reader)
    fun next(): Int
    val name: String
    val eventType: Int
    val text: String
    companion object {
        const val FEATURE_PROCESS_NAMESPACES = "http://xmlpull.org/v1/doc/features.html#process-namespaces"
        const val START_DOCUMENT = 0
        const val END_DOCUMENT = 1
        const val START_TAG = 2
        const val END_TAG = 3
        const val TEXT = 4
        const val CDSECT = 5
    }
}