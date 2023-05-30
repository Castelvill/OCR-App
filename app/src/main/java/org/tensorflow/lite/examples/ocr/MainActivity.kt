/* Copyright 2021 The TensorFlow Authors. All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
==============================================================================
*/

package org.tensorflow.lite.examples.ocr

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory
import com.bumptech.glide.Glide
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors


private const val TAG = "MainActivity"

class MainActivity : AppCompatActivity() {

	private lateinit var pickImageLauncher: ActivityResultLauncher<Intent>
	private lateinit var pickImageButton: ImageButton
	private lateinit var saveToStorageImageButton: ImageButton
	private lateinit var saveToCloudImageButton: ImageButton
	private lateinit var resultListButton: ImageButton

	private val defaultImageName = "no_image.jpg"
	private lateinit var viewModel: MLExecutionViewModel
	private lateinit var resultImageView: ImageView
	private lateinit var resultBitmap: Bitmap
	private lateinit var resultString: String
	private lateinit var inputImage: ImageView
	private lateinit var chipsGroup: ChipGroup
	private lateinit var runButton: Button
	private lateinit var textPromptTextView: TextView
	private lateinit var cloudUrlInput: EditText

	private var useGPU = false
	private var ocrModel: OCRModelExecutor? = null
	private val inferenceThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
	private val mainScope = MainScope()
	private val mutex = Mutex()

	private lateinit var db: AppDatabase
	private lateinit var dao: OCRDao
	private var isCurrentResultSaved = true
	private var isCurrentResultSavedToCloud = true

	@RequiresApi(Build.VERSION_CODES.M)
	@SuppressLint("UseSwitchCompatOrMaterialCode")
  	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.tfe_is_activity_main)

		db = AppDatabase.getInstance(this)
		dao = db.OCRDao()

		inputImage = findViewById(R.id.tf_imageview)

		val assetManager = assets
		try {
		  	val tfInputStream: InputStream = assetManager.open(defaultImageName)
		  	val tfBitmap = BitmapFactory.decodeStream(tfInputStream)
		  	inputImage.setImageBitmap(tfBitmap)
		} catch (e: IOException) {
		  	Log.e(TAG, "Failed to open a test image")
		}
		pickImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
			if (result.resultCode == RESULT_OK && result.data != null) {
				val imageUri = result.data?.data
				if (imageUri != null) {
					// Load the selected image using Glide
					Glide.with(this)
					.load(imageUri)
					.into(inputImage)
				}
			}
		}
		pickImageButton = findViewById(R.id.pickImageButton)
		pickImageButton.setOnClickListener {
			// Check for permission to read external storage
			if (ContextCompat.checkSelfPermission(
					this,
					Manifest.permission.READ_EXTERNAL_STORAGE
			) == PackageManager.PERMISSION_GRANTED
			) {
			// Launch the gallery intent to pick an image
				val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
				pickImageLauncher.launch(intent)
			} else {
			// Request permission to read external storage
				requestPermissions(
					arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
					READ_EXTERNAL_STORAGE_PERMISSION_REQUEST_CODE
				)
			}
		}



		resultListButton = findViewById(R.id.resultListButton)
		resultListButton.setOnClickListener{
			val intent = Intent(this, ResultListActivity::class.java)
			startActivity(intent)
		}

		saveToStorageImageButton = findViewById(R.id.saveToStorage)
		saveToStorageImageButton.setOnClickListener{
			if(!isCurrentResultSaved){
				val currentResult = OCR(resultImage = getByteArrayFromImage(resultBitmap), resultText = resultString)
				dao.insertResult(currentResult)
				isCurrentResultSaved = true
				saveToStorageImageButton.background = ResourcesCompat.getDrawable(
					resources,
					R.color.tfe_color_accent, null
				)
			}
		}

		cloudUrlInput = findViewById(R.id.cloudUrlInput)

		saveToCloudImageButton = findViewById(R.id.saveToCloud)
		saveToCloudImageButton.setOnClickListener{
			if(!isCurrentResultSavedToCloud){
				mainScope.async(inferenceThread){
					saveToCloudImageButton.background = ResourcesCompat.getDrawable(
						resources,
						R.color.tfe_color_accent, null
					)
					isCurrentResultSavedToCloud = true
					postRequest(cloudUrlInput.text.toString())
				}

			}
		}

		resultImageView = findViewById(R.id.result_imageview)
		chipsGroup = findViewById(R.id.chips_group)
		textPromptTextView = findViewById(R.id.text_prompt)
		val useGpuSwitch: Switch = findViewById(R.id.switch_use_gpu)

		viewModel = AndroidViewModelFactory(application).create(MLExecutionViewModel::class.java)
		viewModel.resultingBitmap.observe(
		  this
		) { resultImage ->
		  if (resultImage != null) {
			updateUIWithResults(resultImage)
		  }
		  enableControls(true)
		}

		mainScope.async(inferenceThread) { createModelExecutor(useGPU) }

		useGpuSwitch.setOnCheckedChangeListener { _, isChecked ->
		  useGPU = isChecked
		  mainScope.async(inferenceThread) { createModelExecutor(useGPU) }
		}

		runButton = findViewById(R.id.rerun_button)
		runButton.setOnClickListener {
		  enableControls(false)

		  mainScope.async(inferenceThread) {
			mutex.withLock {
			  if (ocrModel != null) {
				viewModel.onApplyModel(ocrModel, inferenceThread, inputImage.drawable.toBitmap())
			  } else {
				Log.d(
				  TAG,
				  "Skipping running OCR since the ocrModel has not been properly initialized ..."
				)
			  }
			}
		  }
		}

		setChipsToLogView(HashMap())
		enableControls(true)
	}

	//"text", resultString
	//"image", convertToBase64String(resultBitmap)

	@Throws(IOException::class)
	fun postRequest(url: String) {
		// create your json here
		val jsonObject = JSONObject()
		try {
			jsonObject.put("text", resultString)
			jsonObject.put("image", "data:image/jpeg;base64,"+convertToBase64String(resultBitmap))
		} catch (e : JSONException) {
			e.printStackTrace()
		}

		val client = OkHttpClient()
		val mediaType = "application/json; charset=utf-8".toMediaType()
		val body = jsonObject.toString().toRequestBody(mediaType)
		val request: Request = Request.Builder()
			.url(url)
			.post(body)
			.build()

		val response: Response?
		try {
			response = client.newCall(request).execute()
			response.body!!.string()
			Log.d("HEH", convertToBase64String(resultBitmap))
		} catch (e: IOException) {
			e.printStackTrace()
		}
	}

	/*@Throws(IOException::class)
	fun post(url: String, json: String): String? {
		val request = Request.Builder()
			.url(url)
			.post(json.toRequestBody(JSON))
			.build()
		client.newCall(request).execute().use { response -> return response.body!!.string() }
	}*/

	private fun getByteArrayFromImage(image: Bitmap) : ByteArray {
		val stream = ByteArrayOutputStream()
		image.compress(Bitmap.CompressFormat.PNG, 100, stream)
		return stream.toByteArray()
	}

	private fun convertToBase64String(bitmap: Bitmap): String {
		val outputStream = ByteArrayOutputStream()
		bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
		return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
	}

	override fun dispatchTouchEvent(event: MotionEvent): Boolean {
		if (event.action == MotionEvent.ACTION_DOWN) {
			val v = currentFocus
			if (v is EditText) {
				val outRect = Rect()
				v.getGlobalVisibleRect(outRect)
				if (!outRect.contains(event.rawX.toInt(), event.rawY.toInt())) {
					v.clearFocus()
					val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
					imm.hideSoftInputFromWindow(v.getWindowToken(), 0)
				}
			}
		}
		return super.dispatchTouchEvent(event)
	}

	override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults)
		if (requestCode == READ_EXTERNAL_STORAGE_PERMISSION_REQUEST_CODE) {
			  if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
				// Permission granted, launch the gallery intent to pick an image
				val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
				pickImageLauncher.launch(intent)
			  }
		}
	}

	companion object {
	  private const val READ_EXTERNAL_STORAGE_PERMISSION_REQUEST_CODE = 100
	}

	private suspend fun createModelExecutor(useGPU: Boolean) {
		mutex.withLock {
			if (ocrModel != null) {
				ocrModel!!.close()
				ocrModel = null
			}
			try {
				ocrModel = OCRModelExecutor(this, useGPU)
			} catch (e: Exception) {
				Log.e(TAG, "Fail to create OCRModelExecutor: ${e.message}")
				val logText: TextView = findViewById(R.id.log_view)
				logText.text = e.message
			}
		}
	}

	private fun setChipsToLogView(itemsFound: Map<String, Int>) {
		chipsGroup.removeAllViews()

		for ((word, color) in itemsFound) {
		  val chip = Chip(this)
		  chip.text = word
		  chip.chipBackgroundColor = getColorStateListForChip(color)
		  chip.isClickable = false
		  chipsGroup.addView(chip)
		}
		val labelsFoundTextView: TextView = findViewById(R.id.tfe_is_labels_found)
		if (chipsGroup.childCount == 0) {
		  labelsFoundTextView.text = getString(R.string.tfe_ocr_no_text_found)
		} else {
		  labelsFoundTextView.text = getString(R.string.tfe_ocr_texts_found)
		}
		chipsGroup.parent.requestLayout()
	}

	private fun convertChipsToString(itemsFound: Map<String, Int>) : String{
		var resultString = ""
		for ((word, _) in itemsFound) {
			resultString += "$word, "
		}
		return resultString
	}

	private fun getColorStateListForChip(color: Int): ColorStateList {
	val states =
	  arrayOf(
		intArrayOf(android.R.attr.state_enabled), // enabled
		intArrayOf(android.R.attr.state_pressed) // pressed
	  )

	val colors = intArrayOf(color, color)
	return ColorStateList(states, colors)
	}

	private fun setImageView(imageView: ImageView, image: Bitmap) {
		Glide.with(baseContext).load(image).override(250, 250).fitCenter().into(imageView)
	}

	private fun updateUIWithResults(modelExecutionResult: ModelExecutionResult) {
		resultBitmap = modelExecutionResult.bitmapResult
		resultString = convertChipsToString(modelExecutionResult.itemsFound)
		isCurrentResultSaved = false
		isCurrentResultSavedToCloud = false

		saveToStorageImageButton.background = ResourcesCompat.getDrawable(
			resources,
			R.color.tfe_color_primary_dark, null
		)
		saveToCloudImageButton.background = ResourcesCompat.getDrawable(
			resources,
			R.color.tfe_color_primary_dark, null
		)

		setImageView(resultImageView, resultBitmap)
		val logText: TextView = findViewById(R.id.log_view)
		logText.text = modelExecutionResult.executionLog

		setChipsToLogView(modelExecutionResult.itemsFound)
		enableControls(true)
	}

	private fun enableControls(enable: Boolean) {
		runButton.isEnabled = enable
	}
}
