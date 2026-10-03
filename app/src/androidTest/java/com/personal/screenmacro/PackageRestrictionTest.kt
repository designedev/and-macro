package com.personal.screenmacro

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import com.personal.screenmacro.core.*
import com.personal.screenmacro.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses an isolated in-memory DB and never touches the user's macros or permissions. */
@RunWith(AndroidJUnit4::class)
class PackageRestrictionTest {
    @Test fun legacyPackageColumnIsIgnoredAndSettingsRemainReadable() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, MacroDatabase::class.java).build()
        try {
            val original = Macro(name = "호환성 검증", query = "확인", intervalMs = 3000)
            val legacy = MacroRow.from(original).copy(targetPackage = "com.nexon.devcat.mm")
            database.macros().put(legacy)
            val read = database.macros().get(original.id)!!.model()
            read.validate()
            assertEquals(original, read)
            database.macros().put(legacy.copy(targetPackage = "com.example.anything"))
            database.macros().get(original.id)!!.model().validate()
            assertTrue(ApplicationPolicy.canAutomate("com.nexon.devcat.mmgalaxy", context.packageName))
            assertTrue(ApplicationPolicy.canAutomate("com.example.unrelated", context.packageName))
        } finally { database.close() }
    }
}
