package org.tensorflow.lite.examples.ocr

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase


@Entity
data class OCR(
	@PrimaryKey(autoGenerate = true) val id: Int = 0,
	@ColumnInfo(name = "resultImage", typeAffinity = ColumnInfo.BLOB)
	var resultImage: ByteArray,
	@ColumnInfo(name = "resultText") var resultText: String?
) {
	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (javaClass != other?.javaClass) return false

		other as OCR

		if (!resultImage.contentEquals(other.resultImage)) return false

		return true
	}

	override fun hashCode(): Int {
		return resultImage.contentHashCode()
	}
}

@Dao
interface OCRDao {
	@Query("SELECT * FROM OCR ORDER BY id desc")
	fun getAll(): Array<OCR>

	@Query("SELECT * FROM OCR WHERE id IN (:Ids)")
	fun loadAllByIds(Ids: IntArray): List<OCR>

	@Query("SELECT * FROM OCR WHERE resultText LIKE :text LIMIT 1")
	fun findByText(text: String): OCR

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	fun insertResult(ocr: OCR)

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	fun insertAll(vararg OCRs: OCR)

	@Delete
	fun delete(ocr: OCR)

	@Query("DELETE FROM OCR WHERE id = :ocrId")
	fun deleteByOCRId(ocrId: Long)
}

@Database(entities = [OCR::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
	abstract fun OCRDao(): OCRDao

	companion object {
		private var instance: AppDatabase? = null
		fun getInstance(context: Context): AppDatabase {
			if (instance == null) {
				instance = Room.databaseBuilder(context,AppDatabase::class.java,"ocr-database.db")
					.allowMainThreadQueries()
					.build()
			}
			return instance as AppDatabase
		}
	}
}