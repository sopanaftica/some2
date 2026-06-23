package com.example.data

import kotlinx.coroutines.flow.Flow

class ProfileRepository(private val profileDao: ProfileDao) {
    val allProfiles: Flow<List<ProfileEntity>> = profileDao.getAllProfiles()

    suspend fun getProfileById(id: Int): ProfileEntity? {
        return profileDao.getProfileById(id)
    }

    suspend fun insert(profile: ProfileEntity): Long {
        return profileDao.insertProfile(profile)
    }

    suspend fun delete(profile: ProfileEntity) {
        profileDao.deleteProfile(profile)
    }

    suspend fun deleteById(id: Int) {
        profileDao.deleteProfileById(id)
    }
}
