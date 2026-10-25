package com.upr1se.kadr.data.db

import androidx.room.TypeConverter

class Converters {
  @TypeConverter
  fun contentStateToString(value: ContentState): String = value.name

  @TypeConverter
  fun stringToContentState(value: String): ContentState = ContentState.valueOf(value)

  @TypeConverter
  fun readingStateToString(value: ReadingState): String = value.name

  @TypeConverter
  fun stringToReadingState(value: String): ReadingState = ReadingState.valueOf(value)
}
