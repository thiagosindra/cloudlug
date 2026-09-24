package dev.thiagosindra.cloudlug.scheduling

import android.content.Context
import android.os.Build
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.thiagosindra.cloudlug.database.TransferRepository
import javax.inject.Singleton

/**
 * Which §17 scheduler this device gets.
 *
 * The version check is here and nowhere else: neither scheduler knows the other
 * exists, and everything above them depends on [TransferScheduler] alone.
 */
@Module
@InstallIn(SingletonComponent::class)
object SchedulingModule {

    @Provides
    @Singleton
    fun workManager(@ApplicationContext context: Context): WorkManager = WorkManager.getInstance(context)

    @Provides
    @Singleton
    fun scheduler(
        @ApplicationContext context: Context,
        work: WorkManager,
        repository: TransferRepository,
    ): TransferScheduler = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        // §17: UIDT above the dataSync cap, WorkManager below it.
        UidtTransferScheduler(context, repository)
    } else {
        WorkManagerTransferScheduler(work, repository)
    }
}
