package com.dante.smbtv

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.dante.smbtv.databinding.ItemEntryBinding

class EntryAdapter(
    private val ctx: Context,
    private val onClick: (Entry) -> Unit,
    private val onLong: (Entry) -> Boolean
) : RecyclerView.Adapter<EntryAdapter.VH>() {

    class VH(val b: ItemEntryBinding) : RecyclerView.ViewHolder(b.root)

    var items: List<Entry> = emptyList()
        private set

    fun submit(list: List<Entry>, reset: Boolean = false) {
        val old = items
        if (reset) {
            items = list
            notifyDataSetChanged()
            return
        }
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = list.size
            override fun areItemsTheSame(o: Int, n: Int) = old[o].url == list[n].url
            override fun areContentsTheSame(o: Int, n: Int) = old[o] == list[n]
        })
        items = list
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val e = items[position]
        h.b.title.text = e.name

        var sub = e.subtitle
        if (e.kind == Kind.VIDEO) {
            Store.resume(ctx, e.url)?.let { (p, d) ->
                sub += "   •   Resume ${fmtTime(p)} / ${fmtTime(d)}"
            }
        }
        h.b.sub.text = sub

        val (res, tint) = when (e.kind) {
            Kind.SERVER -> R.drawable.ic_server to 0xFF5CE1E6.toInt()
            Kind.SHARE -> R.drawable.ic_folder to 0xFF5CE1E6.toInt()
            Kind.FOLDER -> R.drawable.ic_folder to 0xFFFFC857.toInt()
            Kind.VIDEO -> R.drawable.ic_movie to 0xFFFF7A3D.toInt()
        }
        h.b.icon.setImageResource(res)
        h.b.icon.imageTintList = ColorStateList.valueOf(tint)

        h.b.root.setOnClickListener { onClick(e) }
        h.b.root.setOnLongClickListener { onLong(e) }
        h.b.root.setOnFocusChangeListener { v, focused ->
            val s = if (focused) 1.02f else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(120).start()
        }
    }
}
