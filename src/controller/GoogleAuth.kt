package com.ktor.assignment.controller

import com.google.gson.Gson
import com.ktor.assignment.models.Address
import com.ktor.assignment.models.Addresses
import com.ktor.assignment.models.User
import com.ktor.assignment.models.Users
import io.ktor.application.*
import io.ktor.auth.*
import io.ktor.client.*
import io.ktor.client.engine.jetty.*
import io.ktor.client.request.*
import io.ktor.features.*
import io.ktor.freemarker.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.sessions.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import java.lang.Exception

val httpClient = HttpClient(Jetty)
val dbUrl=System.getenv("DATABASE_URL")
val mysqlUser=System.getenv("MYSQL_USER")
val mysqlPassword=System.getenv("MYSQL_PASSWORD")
val googleClientId=System.getenv("CLIENT_ID")
val googleClientSecret=System.getenv("CLIENT_SECRET")
var googleOAuthProvider= OAuthServerSettings.OAuth2ServerSettings(
        name = "google",
        authorizeUrl = "https://accounts.google.com/o/oauth2/auth",
        accessTokenUrl = "https://www.googleapis.com/oauth2/v3/token",
        requestMethod = HttpMethod.Post,

        clientId = googleClientId,
        clientSecret = googleClientSecret,
        defaultScopes = listOf("profile","email")
)

fun Application.authenticationModule(){
    install(Authentication) {
        oauth ("GoogleAuth"){
            client= httpClient
            providerLookup= { googleOAuthProvider }
            urlProvider={redirectUrl("/login")}

        }
    }

    Database.connect(dbUrl,
            "com.mysql.cj.jdbc.Driver", mysqlUser, mysqlPassword)

    routing {
        authenticate("GoogleAuth") {
            route("/login") {
                handle{

                    val principal = call.authentication.principal<OAuthAccessTokenResponse.OAuth2>()
                    if (principal!=null) {
                        val json = httpClient.get<String>("https://www.googleapis.com/userinfo/v2/me") {
                            header("Authorization", "Bearer ${principal.accessToken}")
                        }

                        val user=Gson().fromJson(json, UserResponse::class.java)

                        if (user.id != null) {
                            transaction {
                                   User.find{(Users.email eq user.email)}.let {
                                    if (it.empty())
                                        User.new{
                                            name=user.name
                                            email=user.email
                                            username=user.email
                                            accessToken=principal.accessToken
                                            profilePicture=user.picture
                                        }
                                    else it.iterator().next()
                                }
                            }
                            call.sessions.set<AuthSession>(AuthSession(json))
                            call.respondRedirect("/setup")
                            return@handle



                        }
                        call.respondRedirect("/")
                    }
                }
            }
        }

        route ("/logout") {
            handle {
                call.sessions.clear<AuthSession>()
                call.respondRedirect("/")
            }
        }
        route("/setup" ) {
            get {
                val auth=call.sessions.get<AuthSession>()
                if (auth!=null) {
                    val user=Gson().fromJson(auth.userId, UserResponse::class.java)
                    var dbUser: User?=null
                    var profileLock=false
                    var addresses:List<Address>?=null
                    transaction{
                        User.find((Users.email eq user.email) and (Users.name eq user.name)).let{
                            if(!it.empty()){
                                dbUser=it.iterator().next()
                                addresses= Address.find(Addresses.user eq dbUser!!.id).iterator().asSequence().toList()
                                profileLock=dbUser!!.profileLock
                            }
                        }
                    }
                    call.respond(FreeMarkerContent("setup.ftl",mapOf("user" to dbUser,"locked" to profileLock,"Addresses" to addresses),""))
                }
            }
            post {
                val auth=call.sessions.get<AuthSession>()
                if (auth!=null) {

                    val user=Gson().fromJson(auth.userId, UserResponse::class.java)
                    val params=call.receive<ProfileRequest>()

                    transaction{
                        User.find((Users.email eq user.email) and (Users.name eq user.name)).let{
                            if(!it.empty()){
                                it.iterator().next().apply {
                                    if (params.mobileNumber.matches(Regex("^[7-9][0-9]{9}$")))
                                        this.mobileNumber=params.mobileNumber

                                    if (!this.profileLock && params.username!="")
                                    {
                                        User.find(Users.username eq params.username).let {userList->
                                            if (userList.empty()){
                                                this.username=params.username
                                                this.profileLock=true
                                            }

                                        }

                                    }
                                }

                            }
                        }


                    }
                    call.respond (mapOf("success" to true,"message" to "Operation Successful"))



                }

            }
        }

        route("/address") {
            post {
                val auth=call.sessions.get<AuthSession>()
                try {
                    if (auth != null) {
                        val user = Gson().fromJson(auth.userId, UserResponse::class.java)
                        var dbUser: User? = null
                        val params = call.receiveParameters()
                        transaction {
                            val dbUserList = User.find((Users.email eq user.email) and (Users.name eq user.name))
                            if (!dbUserList.empty()) {
                                dbUser = dbUserList.iterator().next()
                            }
                            Address.new {
                                title = params["title"]!!
                                line1 = params["line1"]!!
                                line2 = params["line2"]!!
                                locality = params["locality"]!!
                                city = params["city"]!!
                                state = params["state"]!!
                                pincode = params["pincode"]!!.toInt()
                                active = true
                                this.user = dbUser!!

                            }
                        }
                        call.respond(mapOf("success" to true, "message" to "Operation Successful"))
                    } else {
                        call.respond(mapOf("success" to false, "message" to "Missing Authentication"))
                    }
                } catch (e:Exception) {
                    call.respond(mapOf("success" to false, "message" to e.message))
                }
            }

            delete("/{id}") {
                val id = call.parameters["id"]
                val auth = call.sessions.get<AuthSession>()
                try {
                    if (auth != null) {
                        val user = Gson().fromJson(auth.userId, UserResponse::class.java)
                        var dbUser: User? = null
                        transaction {
                            val dbUserList = User.find((Users.email eq user.email) and (Users.name eq user.name))
                            if (!dbUserList.empty()) {
                                dbUser = dbUserList.iterator().next()
                            }
                            val addressList = Address.find((Addresses.id eq id!!.toLong()) and (Addresses.user eq dbUser!!.id)).iterator()
                            if (addressList.hasNext()) {
                                addressList.next().delete()
                            }
                        }

                            call.respond(mapOf("success" to true, "message" to "Operation Successful"))

                    } else {
                        call.respond(mapOf("success" to false, "message" to "Missing Authentication"))
                    }
                } catch (e: Exception) {
                    call.respond(mapOf("success" to false, "message" to e.message))
                }
            }

        }
    }

}




private fun ApplicationCall.redirectUrl(path:String):String {
    val defaultPort=  if (request.origin.scheme=="http") 80 else 443
    val hostPort=request.host()+request.port().let{ port -> if (port == defaultPort) "" else ":$port"}
    val protocol = request.origin.scheme
    return "$protocol://$hostPort$path"
}

data class UserResponse(val id:String,val email:String,val name:String,val picture:String)

data class ProfileRequest(val username: String, val mobileNumber : String)