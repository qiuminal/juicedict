package com.qiuminal.juicedict.data

import android.os.Parcel
import android.os.Parcelable

/** User-visible metadata for one installed StarDict dictionary. */
data class DictionaryInfo(
    val id: String,
    val bookName: String,
    val baseName: String,
    val wordCount: Long,
    val description: String,
    val author: String,
    val date: String,
    val version: String,
    val dictFileName: String,
    val bundled: Boolean,
    val enabled: Boolean,
) : Parcelable {

    constructor(parcel: Parcel) : this(
        parcel.readString() ?: "",
        parcel.readString() ?: "",
        parcel.readString() ?: "",
        parcel.readLong(),
        parcel.readString() ?: "",
        parcel.readString() ?: "",
        parcel.readString() ?: "",
        parcel.readString() ?: "",
        parcel.readString() ?: "",
        parcel.readByte().toInt() != 0,
        parcel.readByte().toInt() != 0,
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(id)
        parcel.writeString(bookName)
        parcel.writeString(baseName)
        parcel.writeLong(wordCount)
        parcel.writeString(description)
        parcel.writeString(author)
        parcel.writeString(date)
        parcel.writeString(version)
        parcel.writeString(dictFileName)
        parcel.writeByte(if (bundled) 1 else 0)
        parcel.writeByte(if (enabled) 1 else 0)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<DictionaryInfo> {
            override fun createFromParcel(parcel: Parcel): DictionaryInfo = DictionaryInfo(parcel)
            override fun newArray(size: Int): Array<DictionaryInfo?> = arrayOfNulls(size)
        }
    }
}
