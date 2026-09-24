package com.ians.korno.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * Прореженный силуэт трека для миниатюры в списке. Считается, когда трек пишется целиком
 * (`insertTrack`/`replaceTrack`), чтобы список не читал ради картинки тысячи точек.
 *
 * Отдельной таблицей, а не колонкой `activities`: `updateActivity` пишет строку целиком из
 * доменной модели, и превью в ней затиралось бы при каждой правке названия.
 *
 * [points] — пары широта/долгота подряд, по 8 байт на число; пустой массив — «посчитано,
 * рисовать нечего», чтобы дозаполнение не возвращалось к такой записи.
 */
@Entity(
    tableName = "route_previews",
    foreignKeys = [
        ForeignKey(
            entity = ActivityEntity::class,
            parentColumns = ["id"],
            childColumns = ["activity_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
class RoutePreviewEntity(
    @PrimaryKey
    @ColumnInfo(name = "activity_id")
    val activityId: Long,
    val points: ByteArray,
)
