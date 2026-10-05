package androidx.recyclerview.widget
import android.content.Context
import android.view.View
import android.view.ViewGroup
open class RecyclerView(context: Context?) : ViewGroup(context) {
    abstract class Adapter<VH : ViewHolder> {
        abstract fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH
        abstract fun onBindViewHolder(holder: VH, position: Int)
        abstract fun getItemCount(): Int
        fun notifyDataSetChanged() {}
    }
    abstract class ViewHolder(val itemView: View)
    abstract class LayoutManager
    var layoutManager: LayoutManager? = null
    var adapter: Adapter<*>? = null
    fun scrollToPosition(position: Int) {}
}
open class LinearLayoutManager(context: Context?) : RecyclerView.LayoutManager()