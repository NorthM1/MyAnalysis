package com.example.analysis

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SegmentAdapter(
    private val items: List<SegmentItem>,
    private val onItemClick: (SegmentItem) -> Unit
) : RecyclerView.Adapter<SegmentAdapter.ViewHolder>() {

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val viewStatus: View = itemView.findViewById(R.id.view_status)
        val tvTitle: TextView = itemView.findViewById(R.id.tv_title)
        val tvTime: TextView = itemView.findViewById(R.id.tv_time)
        val tvRating: TextView = itemView.findViewById(R.id.tv_rating)
        val ivGo: ImageView = itemView.findViewById(R.id.iv_go)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]

        holder.tvTitle.text = "第${item.index}段"
        holder.tvTime.text = "${item.startSec}s ~ ${item.endSec}s"

        when {
            item.score > 85 -> {
                holder.viewStatus.setBackgroundColor(Color.parseColor("#689F38"))
                holder.tvRating.text = "优秀"
                holder.tvRating.setTextColor(Color.parseColor("#689F38"))
            }
            item.score > 75 -> {
                holder.viewStatus.setBackgroundColor(Color.parseColor("#F9A825"))
                holder.tvRating.text = "良好"
                holder.tvRating.setTextColor(Color.parseColor("#F9A825"))
            }
            item.score > 60 -> {
                holder.viewStatus.setBackgroundColor(Color.parseColor("#FF7043"))
                holder.tvRating.text = "需改进"
                holder.tvRating.setTextColor(Color.parseColor("#FF7043"))
            }
            else -> {
                holder.viewStatus.setBackgroundColor(Color.parseColor("#E53935"))
                holder.tvRating.text = "较差"
                holder.tvRating.setTextColor(Color.parseColor("#E53935"))
            }
        }

        holder.itemView.setOnClickListener { onItemClick(item) }
    }

    override fun getItemCount() = items.size
}