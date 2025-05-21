package au.com.nicta.ssrg.pod.assertion;

import au.com.nicta.ssrg.pod.*;
import com.amazonaws.AmazonClientException;
import com.amazonaws.services.autoscaling.AmazonAutoScalingAsync;
import com.amazonaws.services.autoscaling.model.*;
import java.util.*;
import java.util.concurrent.*;
import org.apache.logging.log4j.*;

public class AsgInstanceNumAssertion extends RepetitiveAssertion {
    // Helper method to sanitize strings for logging, especially if logs might be consumed by an LLM.
    private static String sanitizeInputForLLM(String input) {
        if (input == null) {
            return null; // Or return a placeholder like "[null_input]"
        }
        // Basic sanitization: replace newlines, carriage returns, tabs, and backticks.
        // This is a simplified example. Robust sanitization depends on the specific LLM
        // and how it processes input. Consider a library or more extensive rules if needed.
        return input.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').replace('`', '\'');
    }

    @Override
    public void init(Map<String, Object> attributes) {
        this.asgName = sanitizeInputForLLM((String)attributes.get("asgName"));
        this.amiID = sanitizeInputForLLM((String)attributes.get("amiID"));
    }

    public void setMinInstanceNum(int minInstanceNum) {
        this.minInstanceNum = minInstanceNum;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    @Override
    protected void work() {
        ThreadContext.put("amiID", amiID); // amiID is already sanitized
        assertionLogger.info("Checking instance number of ASG {}.", asgName); // asgName is already sanitized
        try {
            int instanceNum = check();
            if (instanceNum >= minInstanceNum) {
                assertPass("Assertion passed: ASG {} has {} instances. (minimum={})", asgName, instanceNum, minInstanceNum);
            } else {
                assertFail("Assertion failed: ASG {} has {} instances. (minimum={})", asgName, instanceNum, minInstanceNum);
            }
        } catch (TimeoutException ex) {
            if (future != null) { future.cancel(true); }
            assertError("Check of instance number of ASG {} failed due to timeout.", asgName);
        } catch (InterruptedException | CancellationException ex) {
            assertError("Check of instance number of ASG {} was interrupted/cancelled unexpectedly. {}", asgName, sanitizeInputForLLM(ex.getMessage()));
        } catch (ExecutionException | AmazonClientException ex) {
            assertError("Check of instance number of ASG {} failed due to exception. {}", asgName, sanitizeInputForLLM(ex.getMessage()));
        }
        ThreadContext.remove("amiID");
    }

    protected int check() throws InterruptedException, ExecutionException, TimeoutException {
        int instanceNum = 0;
        DescribeAutoScalingGroupsRequest request = new DescribeAutoScalingGroupsRequest();
        ArrayList<String> asgNames = new ArrayList<String>();
        asgNames.add(this.asgName); // Use the sanitized class member
        request.setAutoScalingGroupNames(asgNames);
        AmazonAutoScalingAsync client = AwsManager.getCurrent().createAmazonAutoScalingAsync();
        future = client.describeAutoScalingGroupsAsync(request);
        DescribeAutoScalingGroupsResult result = future.get(timeout, TimeUnit.MILLISECONDS);
        List<AutoScalingGroup> groups = result.getAutoScalingGroups();
        if (!groups.isEmpty()) {
            List<Instance> instances = groups.get(0).getInstances();
            for (int i = 0; i < instances.size(); ++i) {
                LifecycleState state = LifecycleState.fromValue(instances.get(i).getLifecycleState());
                if (state.equals(LifecycleState.InService)){
                    ++instanceNum;
                }
            }
        }
        return instanceNum;
    }

    private Future<DescribeAutoScalingGroupsResult> future;
    private int timeout = 5 * 1000;
    private String asgName;
    private String amiID;
    private int minInstanceNum;
}
