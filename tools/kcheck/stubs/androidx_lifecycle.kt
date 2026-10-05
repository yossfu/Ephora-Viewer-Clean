package androidx.lifecycle
interface LifecycleOwner
val LifecycleOwner.lifecycleScope: kotlinx.coroutines.CoroutineScope
    get() = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)