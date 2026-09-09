package game.wreckriff.diagnostics;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosticCompletionTest {
    @Test void successfulChecksBecomePassOnlyAfterShutdownCompletes() {
        DiagnosticCompletion completion=new DiagnosticCompletion();
        assertFalse(completion.passed());assertTrue(completion.requestStop(true));
        assertFalse(completion.passed(),"A successful game loop cannot certify unfinished teardown");
        assertFalse(completion.requestStop(true),"Stop is dispatched once");
        completion.shutdownCompleted();assertTrue(completion.passed());
        completion.shutdownCompleted();assertTrue(completion.passed());
    }

    @Test void closingTheWindowWithoutSuccessfulChecksIsNotAPass() {
        DiagnosticCompletion completion=new DiagnosticCompletion();
        completion.shutdownCompleted();assertFalse(completion.passed());
    }

    @Test void failedChecksCannotBeReplacedByALaterSuccessfulRequest() {
        DiagnosticCompletion completion=new DiagnosticCompletion();
        assertTrue(completion.requestStop(false));
        assertFalse(completion.requestStop(true));
        completion.shutdownCompleted();assertFalse(completion.passed());
    }

    @Test void repeatedFailedStopRequestInvalidatesSuccessWithoutDispatchingStopAgain() {
        DiagnosticCompletion completion=new DiagnosticCompletion();
        assertTrue(completion.requestStop(true));completion.shutdownCompleted();assertTrue(completion.passed());
        assertFalse(completion.requestStop(false));assertFalse(completion.passed());
        assertFalse(completion.requestStop(true));completion.shutdownCompleted();assertFalse(completion.passed());
    }

    @Test void teardownFailureRemainsLatchedBeforeDuringOrAfterCompletion() {
        for(int failureStage=0;failureStage<3;failureStage++) {
            DiagnosticCompletion completion=new DiagnosticCompletion();
            if(failureStage==0)completion.shutdownFailed();
            completion.requestStop(true);
            if(failureStage==1)completion.shutdownFailed();
            completion.shutdownCompleted();
            if(failureStage==2)completion.shutdownFailed();
            assertFalse(completion.passed(),"Failure stage "+failureStage);
        }
    }

    @Test void concurrentRequestsDispatchOneStopAndCannotLoseAFailure() throws Exception {
        DiagnosticCompletion completion=new DiagnosticCompletion();
        CountDownLatch start=new CountDownLatch(1);
        try(ExecutorService executor=Executors.newFixedThreadPool(4)) {
            var requests=new ArrayList<Future<Boolean>>();
            for(int i=0;i<32;i++) {
                boolean checksPassed=i!=17;
                requests.add(executor.submit(()->{start.await();return completion.requestStop(checksPassed);}));
            }
            start.countDown();int dispatched=0;
            for(Future<Boolean> request:requests)if(request.get(10,TimeUnit.SECONDS))dispatched++;
            assertEquals(1,dispatched);
        }
        completion.shutdownCompleted();assertFalse(completion.passed());
    }
}
