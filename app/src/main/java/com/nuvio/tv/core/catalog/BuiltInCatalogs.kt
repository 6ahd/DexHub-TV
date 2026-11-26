package com.nuvio.tv.core.catalog

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogRow
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge

/**
 * Dex Hub's own Home rows. Each provider shows up as an addon whose base URL starts with [BuiltInCatalogs.BASE_PREFIX],
 * so Home lays its rows out like any addon's, and the catalog repository asks the provider for the row instead of the web.
 */
interface BuiltInCatalogProvider {
    val addons: Flow<List<Addon>>

    /** Fires when rows already on Home should be read again. */
    val rowChanges: Flow<Unit> get() = emptyFlow()

    fun handles(baseUrl: String?): Boolean

    suspend fun catalog(
        addonBaseUrl: String,
        addonId: String,
        addonName: String,
        catalogId: String,
        catalogName: String,
        type: String,
        skip: Int,
        extraArgs: Map<String, String>
    ): CatalogRow
}

@Singleton
class BuiltInCatalogs @Inject constructor(
    private val providers: Set<@JvmSuppressWildcards BuiltInCatalogProvider>
) {
    val addons: Flow<List<Addon>> = when {
        providers.isEmpty() -> flowOf(emptyList())
        else -> combine(providers.map { it.addons }) { lists -> lists.flatMap { it } }
    }

    val rowChanges: Flow<Unit> = providers.map { it.rowChanges }.merge()

    fun provider(baseUrl: String?): BuiltInCatalogProvider? = providers.firstOrNull { it.handles(baseUrl) }

    fun isBuiltIn(baseUrl: String?): Boolean = baseUrl?.startsWith(BASE_PREFIX) == true

    companion object {
        const val BASE_PREFIX = "dexhub-builtin://"
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BuiltInCatalogsModule {
    @Multibinds
    abstract fun providers(): Set<BuiltInCatalogProvider>
}
