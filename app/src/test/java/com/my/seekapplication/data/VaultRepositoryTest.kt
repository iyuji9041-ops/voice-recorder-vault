package com.my.seekapplication.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Unit tests for VaultRepository:
 * 1. UUID uniqueness: same-name files do NOT overwrite each other.
 * 2. Atomic update: updateItemContent updates plaintext size and contents.
 * 3. Atomic deletion: deleteItem removes file from disk and index.
 */
class VaultRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun generateMasterKey(): SecretKey {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        return keyGen.generateKey()
    }

    @Test
    fun testSameNameFilesReceiveDistinctUUIDsAndDoNotOverwrite() {
        val masterKey = generateMasterKey()
        val vaultDir = tempFolder.newFolder("vault_unique_test")
        val repo = VaultRepository(vaultDir, masterKey, isDecoy = false)

        val file1Data = "Content of photo 1".toByteArray(Charsets.UTF_8)
        val file2Data = "Different content of photo 2 with same name".toByteArray(Charsets.UTF_8)

        val sameName = "IMG_2026.JPG"

        val item1 = repo.importStream(ByteArrayInputStream(file1Data), sameName, "Photos", "image/jpeg")
        val item2 = repo.importStream(ByteArrayInputStream(file2Data), sameName, "Photos", "image/jpeg")

        // Assert distinct UUIDs
        assertNotEquals(item1.id, item2.id)

        // Assert both files physically exist on disk
        val file1OnDisk = repo.getFileForItem(item1)
        val file2OnDisk = repo.getFileForItem(item2)
        assertTrue(file1OnDisk.exists())
        assertTrue(file2OnDisk.exists())
        assertNotEquals(file1OnDisk.absolutePath, file2OnDisk.absolutePath)

        // Assert repository returns both items
        val allItems = repo.getItems("Photos")
        assertEquals(2, allItems.size)

        // Assert decrypting each returns their distinct original content
        val out1 = ByteArrayOutputStream()
        repo.exportItemToStream(item1, out1)
        assertArrayEquals(file1Data, out1.toByteArray())

        val out2 = ByteArrayOutputStream()
        repo.exportItemToStream(item2, out2)
        assertArrayEquals(file2Data, out2.toByteArray())
    }

    @Test
    fun testUpdateItemContentUpdatesPlaintextSizeAndContent() {
        val masterKey = generateMasterKey()
        val vaultDir = tempFolder.newFolder("vault_update_test")
        val repo = VaultRepository(vaultDir, masterKey, isDecoy = false)

        val originalData = "Initial original image data".toByteArray(Charsets.UTF_8)
        val item = repo.importStream(ByteArrayInputStream(originalData), "edit_test.jpg", "Photos", "image/jpeg")
        assertEquals(originalData.size.toLong(), item.fileSize)

        val editedData = "New edited and filtered image data that has different length!".toByteArray(Charsets.UTF_8)
        val updatedItem = repo.updateItemContent(item, ByteArrayInputStream(editedData))

        assertEquals(editedData.size.toLong(), updatedItem.fileSize)
        assertEquals(item.id, updatedItem.id)

        // Verify stored index was updated
        val retrieved = repo.getItemById(item.id)
        assertEquals(editedData.size.toLong(), retrieved?.fileSize)

        // Verify decrypted content matches edited content
        val out = ByteArrayOutputStream()
        repo.exportItemToStream(updatedItem, out)
        assertArrayEquals(editedData, out.toByteArray())
    }

    @Test
    fun testDeleteItemRemovesEncryptedFileAndUpdatesIndex() {
        val masterKey = generateMasterKey()
        val vaultDir = tempFolder.newFolder("vault_delete_test")
        val repo = VaultRepository(vaultDir, masterKey, isDecoy = false)

        val data = "File to be deleted".toByteArray(Charsets.UTF_8)
        val item = repo.importStream(ByteArrayInputStream(data), "delete_me.mp4", "Videos", "video/mp4")

        val fileOnDisk = repo.getFileForItem(item)
        assertTrue(fileOnDisk.exists())
        assertEquals(1, repo.getItems("Videos").size)

        val deleted = repo.deleteItem(item.id)
        assertTrue(deleted)
        assertFalse(fileOnDisk.exists())
        assertEquals(0, repo.getItems("Videos").size)
    }
}
