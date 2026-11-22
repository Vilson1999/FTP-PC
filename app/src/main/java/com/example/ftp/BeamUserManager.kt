package com.example.ftp

import com.example.data.AppSettings
import org.apache.ftpserver.ftplet.Authentication
import org.apache.ftpserver.ftplet.AuthenticationFailedException
import org.apache.ftpserver.ftplet.Authority
import org.apache.ftpserver.ftplet.User
import org.apache.ftpserver.ftplet.UserManager
import org.apache.ftpserver.usermanager.AnonymousAuthentication
import org.apache.ftpserver.usermanager.UsernamePasswordAuthentication
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.ConcurrentLoginPermission
import org.apache.ftpserver.usermanager.impl.TransferRatePermission
import org.apache.ftpserver.usermanager.impl.WritePermission

class BeamUserManager(
    private val settingsProvider: () -> AppSettings
) : UserManager {

    override fun getUserByName(username: String?): User? {
        val settings = settingsProvider()
        val name = username?.trim().orEmpty()
        return if (settings.allowAnonymous) {
            buildUser(if (name.isEmpty()) "anonymous" else name, settings.readOnly)
        } else if (name == settings.username) {
            buildUser(settings.username, settings.readOnly)
        } else {
            null
        }
    }

    override fun getAllUserNames(): Array<String> {
        val settings = settingsProvider()
        return if (settings.allowAnonymous) {
            arrayOf("anonymous", settings.username)
        } else {
            arrayOf(settings.username)
        }
    }

    override fun delete(username: String?) {
        // Managed through app settings
    }

    override fun save(user: User?) {
        // Managed through app settings
    }

    override fun doesExist(username: String?): Boolean {
        return getUserByName(username) != null
    }

    @Throws(AuthenticationFailedException::class)
    override fun authenticate(authentication: Authentication?): User {
        val settings = settingsProvider()
        return when (authentication) {
            is AnonymousAuthentication -> {
                if (!settings.allowAnonymous) {
                    throw AuthenticationFailedException("Anonymous login disabled")
                }
                buildUser("anonymous", settings.readOnly)
            }
            is UsernamePasswordAuthentication -> {
                val user = authentication.username?.trim().orEmpty()
                val pass = authentication.password.orEmpty()
                if (settings.allowAnonymous) {
                    buildUser(user.ifEmpty { "anonymous" }, settings.readOnly)
                } else if (user == settings.username && pass == settings.password) {
                    buildUser(settings.username, settings.readOnly)
                } else {
                    throw AuthenticationFailedException("Invalid username or password")
                }
            }
            else -> throw AuthenticationFailedException("Unsupported authentication type")
        }
    }

    override fun getAdminName(): String = settingsProvider().username

    override fun isAdmin(username: String?): Boolean = username == settingsProvider().username

    private fun buildUser(name: String, readOnly: Boolean): User {
        val authorities = mutableListOf<Authority>(
            ConcurrentLoginPermission(20, 20),
            TransferRatePermission(0, 0)
        )
        if (!readOnly) {
            authorities.add(WritePermission())
        }
        return BaseUser().apply {
            this.name = name
            this.homeDirectory = "/"
            this.enabled = true
            this.maxIdleTime = 0
            this.authorities = authorities
        }
    }
}
