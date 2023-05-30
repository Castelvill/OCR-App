package org.tensorflow.lite.examples.ocr

import android.content.Context
import android.content.DialogInterface
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.ImageButton
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView


class ResultListActivity : AppCompatActivity() {

    private lateinit var resultListButton: ImageButton
    private lateinit var db: AppDatabase
    private lateinit var dao: OCRDao
    private lateinit var resultsArray : ArrayList<Result>
    private lateinit var recyclerView : RecyclerView
    private var deleteResultID : Long = -1
    private var deleteResultPosition : Int = -1

    private fun getImageFromByteArray(bytes: ByteArray): Bitmap? {
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun convertDatabase(OCRArray : Array<OCR>) : ArrayList<Result>{
        val resultArray = ArrayList<Result>()
        for (ocr in OCRArray) {
            val image = getImageFromByteArray(ocr.resultImage)
            val imageAlt = R.drawable.swim
            val text = ocr.resultText ?: "no text detected"
            val result = Result(ocr.id.toLong(), image, imageAlt, text)
            resultArray.add(result)
        }
        return resultArray
    }

    private var dialogClickListener =
        DialogInterface.OnClickListener { dialog, which ->
            when (which) {
                DialogInterface.BUTTON_POSITIVE -> {
                    dao.deleteByOCRId(deleteResultID)
                    resultsArray.removeAt(deleteResultPosition)
                    recyclerView.adapter?.notifyItemRemoved(deleteResultPosition)
                }
                DialogInterface.BUTTON_NEGATIVE -> {}
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result_list)

        db = AppDatabase.getInstance(this)
        dao = db.OCRDao()

        resultsArray = convertDatabase(dao.getAll())

        val resultListAdapter = ResultListAdapter(resultsArray){ result, position ->
            deleteResultID = result.id
            deleteResultPosition = position
            val builder = AlertDialog.Builder(this)
            builder.setMessage("Are you sure?").setPositiveButton("Yes", dialogClickListener)
                .setNegativeButton("No", dialogClickListener).show()
        }

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.adapter = resultListAdapter

        resultListButton = findViewById(R.id.backButton)
        resultListButton.setOnClickListener{
            finish()
        }
    }
}