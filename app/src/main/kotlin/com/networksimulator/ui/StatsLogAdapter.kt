package com.networksimulator.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.networksimulator.R
import com.networksimulator.stats.StatSnapshot

class StatsLogAdapter : ListAdapter<StatSnapshot, StatsLogAdapter.VH>(DIFF) {

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvTime:       TextView = itemView.findViewById(R.id.tvTime)
        val tvStats:      TextView = itemView.findViewById(R.id.tvStats)
        val tvLabel:      TextView = itemView.findViewById(R.id.tvScreenLabel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_stat_row, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val snap = getItem(position)

        holder.tvTime.text = snap.timeLabel
        holder.tvStats.text = buildString {
            append("↑ ${snap.throughputKbps} kbps")
            append("  |  ⏱ ${snap.avgLatencyMs} ms")
            append("  |  ✕ ${String.format("%.1f", snap.lossPercent)}%")
        }

        if (snap.screenLabel != null) {
            holder.tvLabel.visibility = View.VISIBLE
            holder.tvLabel.text       = "📍 ${snap.screenLabel}"
        } else {
            holder.tvLabel.visibility = View.GONE
        }

        // Colour-code by severity
        holder.tvStats.setTextColor(
            when {
                snap.lossPercent > 10 || snap.avgLatencyMs > 800 ->
                    Color.parseColor("#D32F2F")  // red — bad
                snap.lossPercent > 2  || snap.avgLatencyMs > 300 ->
                    Color.parseColor("#F57C00")  // orange — degraded
                else ->
                    Color.parseColor("#388E3C")  // green — acceptable
            }
        )
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<StatSnapshot>() {
            override fun areItemsTheSame(a: StatSnapshot, b: StatSnapshot) =
                a.timestampMs == b.timestampMs
            override fun areContentsTheSame(a: StatSnapshot, b: StatSnapshot) = a == b
        }
    }
}
