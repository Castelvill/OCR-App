package org.tensorflow.lite.examples.ocr

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.recyclerview.widget.RecyclerView

data class Result(
	val id: Long,
	val image: Bitmap?,
	@DrawableRes
	val imageAlt: Int?,
	val text: String
)

class ResultListAdapter(private val resultsArray: ArrayList<Result>,
						private val listener: (Result, Int) -> Unit):
						RecyclerView.Adapter<ResultListAdapter.ResultViewHolder>(){
	class ResultViewHolder(view: View) : RecyclerView.ViewHolder(view) {
		private val resultImageView: ImageView
		private val resultTextView: TextView
		var deleteButton: ImageButton
		private var currentResult: Result? = null

		init {
			// Define click listener for the ViewHolder's View
			resultImageView = view.findViewById(R.id.resultImage)
			resultTextView = view.findViewById(R.id.resultText)
			deleteButton = view.findViewById(R.id.deleteButton)
		}

		fun bind(result: Result) {
			currentResult = result

			resultTextView.text = result.text

			if(result.image != null){
				resultImageView.setImageBitmap(result.image)
			}
			else{
				if (result.imageAlt != null) {
					resultImageView.setImageResource(result.imageAlt)
				} else {
					resultImageView.setImageResource(R.drawable.error)
				}
			}
		}
	}

	// Create new views (invoked by the layout manager)
	override fun onCreateViewHolder(viewGroup: ViewGroup, viewType: Int): ResultViewHolder {
		// Create a new view, which defines the UI of the list item
		val view = LayoutInflater.from(viewGroup.context)
			.inflate(R.layout.result_list_item, viewGroup, false)

		return ResultViewHolder(view)
	}

	// Replace the contents of a view (invoked by the layout manager)
	override fun onBindViewHolder(viewHolder: ResultViewHolder, position: Int) {

		// Get element from your dataset at this position and replace the
		// contents of the view with that element
		val result = resultsArray[position]
		viewHolder.bind(result)
		viewHolder.deleteButton.setOnClickListener { listener(result, position) }
	}

	// Return the size of your dataset (invoked by the layout manager)
	override fun getItemCount() = resultsArray.size

}