package com.configdirector.testing;

import com.configdirector.ConfigDirectorClient;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * A JUnit Jupiter extension that gives each test its own {@link TestClient}.
 *
 * <p>Before each test it creates a test client, seeds it from the {@link BooleanConfigValue},
 * {@link IntegerConfigValue}, {@link FloatConfigValue}, {@link StringConfigValue}, and {@link
 * JsonConfigValue} annotations on the test class and then on the test method, and resolves {@code
 * TestClient} and {@code ConfigDirectorClient} parameters to it. After the test it closes the
 * client. The client starts uninitialized; the test, or the code under test, calls {@code
 * initialize}.
 *
 * <pre>{@code
 * @ExtendWith(ConfigDirectorTestExtension.class)
 * class CheckoutTest {
 *
 *   @Test
 *   @BooleanConfigValue(key = "new-checkout", value = true)
 *   @IntegerConfigValue(key = "max-items", value = 20)
 *   void showsTheNewCheckout(TestClient testClient) { ... }
 * }
 * }</pre>
 *
 * <p>An application context that outlives a test, such as a cached Spring context, keeps one test
 * client of its own instead and resets it with {@link TestClient#replaceValues(java.util.Map)}.
 */
public final class ConfigDirectorTestExtension
    implements BeforeEachCallback, AfterEachCallback, ParameterResolver {

  private static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(ConfigDirectorTestExtension.class);
  private static final String TEST_CLIENT = "testClient";

  /** Creates the extension. JUnit does this for {@code @ExtendWith}. */
  public ConfigDirectorTestExtension() {}

  @Override
  public void beforeEach(ExtensionContext context) {
    TestClient testClient = ConfigDirectorTesting.createTestClient();
    try {
      AnnotatedConfigValues.seed(
          testClient, context.getRequiredTestClass(), context.getRequiredTestMethod());
    } catch (RuntimeException invalid) {
      testClient.close();
      throw invalid;
    }
    context.getStore(NAMESPACE).put(TEST_CLIENT, testClient);
  }

  @Override
  public void afterEach(ExtensionContext context) {
    TestClient testClient = context.getStore(NAMESPACE).remove(TEST_CLIENT, TestClient.class);
    if (testClient != null) {
      testClient.close();
    }
  }

  @Override
  public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext context) {
    Class<?> type = parameterContext.getParameter().getType();
    return type == TestClient.class || type == ConfigDirectorClient.class;
  }

  @Override
  public Object resolveParameter(ParameterContext parameterContext, ExtensionContext context) {
    TestClient testClient = context.getStore(NAMESPACE).get(TEST_CLIENT, TestClient.class);
    if (testClient == null) {
      throw new ParameterResolutionException(
          "No test client exists for this test. The extension creates one before each test, so a"
              + " TestClient or ConfigDirectorClient parameter is only available on test methods"
              + " and per-test lifecycle methods.");
    }
    return parameterContext.getParameter().getType() == TestClient.class
        ? testClient
        : testClient.client();
  }
}
