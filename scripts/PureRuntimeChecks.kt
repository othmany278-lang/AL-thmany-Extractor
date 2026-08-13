import com.althmany.extractor.engine.RuntimeOperation
import com.althmany.extractor.engine.RuntimeOperationCoordinator

fun main() {
    RuntimeOperationCoordinator.resetForTests()
    check(RuntimeOperationCoordinator.tryAcquire(RuntimeOperation.EXTRACTION))
    check(!RuntimeOperationCoordinator.tryAcquire(RuntimeOperation.SCAN))
    check(RuntimeOperationCoordinator.current() == RuntimeOperation.EXTRACTION)
    RuntimeOperationCoordinator.release(RuntimeOperation.EXTRACTION)
    check(RuntimeOperationCoordinator.tryAcquire(RuntimeOperation.PUBLISH))
    RuntimeOperationCoordinator.release(RuntimeOperation.PUBLISH)
    check(RuntimeOperationCoordinator.current() == null)
    println("PureRuntimeChecks: OK")
}
