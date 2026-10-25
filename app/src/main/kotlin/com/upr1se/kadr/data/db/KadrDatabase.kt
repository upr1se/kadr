package com.upr1se.kadr.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
  entities = [BookEntity::class],
  version = 1,
  exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class KadrDatabase : RoomDatabase() {
  abstract fun bookDao(): BookDao
}
