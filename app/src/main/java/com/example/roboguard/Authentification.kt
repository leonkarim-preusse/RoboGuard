package com.example.roboguard

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

@Serializable
class Authentification(var publickey: String) {

    protected var otp = createOTP()
    init{

        this.publickey = Base64.getEncoder().encodeToString(this.publickey.toByteArray())
    }
    protected fun createOTP(): String {
        return UUID.randomUUID().toString()

    }

    internal fun validateOTP(otp: String): Boolean {
        return this.otp == otp
    }
    internal fun createAuthMessage(): String{

        val json = Json.encodeToString(this)
        return json



    }
}
