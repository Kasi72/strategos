package com.drkkr.strategos.data.repo

import com.drkkr.strategos.data.db.StrategosDatabase
import com.drkkr.strategos.data.db.entities.OpponentProfileEntity
import com.drkkr.strategos.domain.game.OpponentModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

class OpponentProfileRepository(db: StrategosDatabase) {
    private val dao = db.opponentProfileDao()

    suspend fun get(name: String): OpponentModel? =
        dao.forName(name)?.let { parseModel(it.modelJson) }

    suspend fun upsert(name: String, model: OpponentModel, sampleSize: Int) {
        dao.deleteByName(name)
        dao.insert(OpponentProfileEntity(
            id = UUID.randomUUID().toString(),
            opponentName = name,
            modelJson = encodeModel(model),
            sampleSize = sampleSize,
            updatedAt = System.currentTimeMillis()
        ))
    }

    private fun encodeModel(model: OpponentModel): String =
        buildJsonObject {
            put("playerId", model.playerId)
            put("epsilon", model.epsilon)
            put("discountFactor", model.discountFactor)
            model.riskAversion?.let { put("riskAversion", it) }
            model.cooperationIndex?.let { put("cooperationIndex", it) }
            model.informationBelief?.let { put("informationBelief", it) }
        }.toString()

    private fun parseModel(json: String): OpponentModel? {
        return try {
            val obj: JsonObject = Json.parseToJsonElement(json).jsonObject
            val playerId = obj["playerId"]?.jsonPrimitive?.content ?: return null
            val epsilon = obj["epsilon"]?.jsonPrimitive?.double ?: return null
            val discountFactor = obj["discountFactor"]?.jsonPrimitive?.double ?: return null
            OpponentModel(
                playerId = playerId,
                epsilon = epsilon,
                discountFactor = discountFactor,
                riskAversion = obj["riskAversion"]?.jsonPrimitive?.doubleOrNull,
                cooperationIndex = obj["cooperationIndex"]?.jsonPrimitive?.doubleOrNull,
                informationBelief = obj["informationBelief"]?.jsonPrimitive?.doubleOrNull
            )
        } catch (_: Exception) { null }
    }
}
