package com.coursekeeper.data.local

import androidx.room.TypeConverter
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class RecordConverters {
    // Task times use device-local wall time, as agreed in D v1.0.
    @TypeConverter fun dateTime(value: String?): LocalDateTime? = value?.let(LocalDateTime::parse)
    @TypeConverter fun dateTime(value: LocalDateTime?): String? = value?.toString()
    @TypeConverter fun date(value: String?): LocalDate? = value?.let(LocalDate::parse)
    @TypeConverter fun date(value: LocalDate?): String? = value?.toString()
    @TypeConverter fun time(value: String?): LocalTime? = value?.let(LocalTime::parse)
    @TypeConverter fun time(value: LocalTime?): String? = value?.toString()
    @TypeConverter fun instant(value: String?): Instant? = value?.let(Instant::parse)
    @TypeConverter fun instant(value: Instant?): String? = value?.toString()
}
