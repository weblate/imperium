/*
 * Imperium, the software collection powering the Xpdustry network.
 * Copyright (C) 2023  Xpdustry
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.xpdustry.imperium.common.storage

import com.xpdustry.imperium.common.application.ImperiumApplication
import com.xpdustry.imperium.common.async.ImperiumScope
import com.xpdustry.imperium.common.config.ImperiumConfig
import com.xpdustry.imperium.common.config.StorageConfig
import io.minio.BucketExistsArgs
import io.minio.GetObjectArgs
import io.minio.GetPresignedObjectUrlArgs
import io.minio.ListObjectsArgs
import io.minio.MakeBucketArgs
import io.minio.MinioAsyncClient
import io.minio.PutObjectArgs
import io.minio.RemoveBucketArgs
import io.minio.RemoveObjectArgs
import io.minio.StatObjectArgs
import io.minio.errors.ErrorResponseException
import io.minio.http.HttpUtils
import io.minio.http.Method
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.InputStream
import java.net.URL
import java.time.Instant
import java.util.concurrent.TimeUnit

class MinioStorage(private val config: ImperiumConfig) : Storage, ImperiumApplication.Listener {
    private lateinit var client: MinioAsyncClient
    private lateinit var httpClient: OkHttpClient

    override fun onImperiumInit() {
        if (config.storage !is StorageConfig.Minio) {
            throw IllegalStateException("The current storage configuration is not Minio")
        }
        // TODO Replace java HttpClient with OkHttp everywhere?
        val timeout = java.time.Duration.ofMinutes(5).toMillis()
        httpClient = HttpUtils.newDefaultHttpClient(timeout, timeout, timeout)
        client = with(config.storage) {
            MinioAsyncClient.builder()
                .endpoint(host, port, secure)
                .credentials(accessKey.value, secretKey.value)
                .httpClient(httpClient)
                .build()
        }

        try {
            client.listBuckets().join()
        } catch (e: Exception) {
            throw RuntimeException("Could not connect to Minio", e)
        }
    }

    override fun onImperiumExit() {
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
        httpClient.cache?.close()
        httpClient.dispatcher.cancelAll()
        httpClient.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS)
    }

    override suspend fun getBucket(name: String, create: Boolean): Bucket? {
        if (client.bucketExists(BucketExistsArgs.builder().bucket(name).build()).await()) {
            return MinioBucket(name)
        }
        if (create) {
            client.makeBucket(MakeBucketArgs.builder().bucket(name).build()).await()
            return MinioBucket(name)
        }
        return null
    }

    override suspend fun listBuckets(): List<Bucket> =
        client.listBuckets().await().map { MinioBucket(it.name()) }

    override suspend fun deleteBucket(name: String) {
        client.removeBucket(RemoveBucketArgs.builder().bucket(name).build()).await()
    }

    private inner class MinioBucket(override val name: String) : Bucket {
        override suspend fun getObject(name: String): S3Object? = try {
            val stats = client.statObject(
                StatObjectArgs.builder()
                    .bucket(this.name)
                    .`object`(name)
                    .build(),
            ).await()
            MinioObject(this.name, name.split("/"), stats.size(), stats.lastModified().toInstant())
        } catch (e: ErrorResponseException) {
            if (e.errorResponse().code() != "NoSuchKey") {
                throw e
            }
            null
        }

        override suspend fun putObject(name: String, stream: InputStream) {
            client.putObject(
                PutObjectArgs.builder()
                    .bucket(this.name)
                    .`object`(name)
                    .stream(stream, -1, DEFAULT_PART_SIZE)
                    .build(),
            ).await()
        }

        override suspend fun listObjects(prefix: String, recursive: Boolean): Flow<S3Object> = withContext(ImperiumScope.IO.coroutineContext) {
            flow {
                client.listObjects(
                    ListObjectsArgs.builder()
                        .bucket(name)
                        .prefix(prefix)
                        .recursive(recursive)
                        .build(),
                ).forEach {
                    launch {
                        emit(it)
                    }
                }
            }
                .map { it.get() }
                .filter { !it.isDir && !it.isDeleteMarker }
                .map { MinioObject(name, it.objectName().split("/"), it.size(), it.lastModified().toInstant()) }
        }

        override suspend fun deleteObject(name: String) {
            client.removeObject(RemoveObjectArgs.builder().bucket(this.name).`object`(name).build()).await()
        }
    }

    private inner class MinioObject(
        private val bucket: String,
        override val path: List<String>,
        override val size: Long,
        override val lastModified: Instant,
    ) : S3Object {
        override suspend fun getStream(): InputStream =
            client.getObject(GetObjectArgs.builder().bucket(bucket).`object`(path.joinToString("/")).build()).await()

        override suspend fun getDownloadUrl(expiration: kotlin.time.Duration): URL =
            withContext(ImperiumScope.IO.coroutineContext) {
                URL(
                    client.getPresignedObjectUrl(
                        GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(bucket)
                            .`object`(path.joinToString("/"))
                            .expiry(expiration.inWholeSeconds.toInt())
                            .build(),
                    ),
                )
            }
    }

    companion object {
        private const val DEFAULT_PART_SIZE = 5 * 1024 * 1024L
    }
}
