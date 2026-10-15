package testutil

import security.AppLockAccess

/** TEST ONLY gate for isolated unit tests; production AgentContainer always injects AppLockManager. */
object TestUnlockedAppLockAccess : AppLockAccess {
    override fun isAppUnlocked(): Boolean = true
}
