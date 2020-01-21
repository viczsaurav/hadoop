/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.mapreduce.v2;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hadoop.mapreduce.MRJobConfig;
import org.apache.hadoop.mapreduce.v2.app.speculate.LegacyTaskRuntimeEstimator;
import org.apache.hadoop.mapreduce.v2.app.speculate.SimpleExponentialTaskRuntimeEstimator;
import org.apache.hadoop.mapreduce.v2.app.speculate.TaskRuntimeEstimator;
import org.junit.Assert;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.v2.api.records.JobState;
import org.apache.hadoop.mapreduce.v2.api.records.TaskAttemptId;
import org.apache.hadoop.mapreduce.v2.api.records.TaskAttemptState;
import org.apache.hadoop.mapreduce.v2.api.records.TaskId;
import org.apache.hadoop.mapreduce.v2.api.records.TaskState;
import org.apache.hadoop.mapreduce.v2.app.MRApp;
import org.apache.hadoop.mapreduce.v2.app.job.Job;
import org.apache.hadoop.mapreduce.v2.app.job.Task;
import org.apache.hadoop.mapreduce.v2.app.job.TaskAttempt;
import org.apache.hadoop.mapreduce.v2.app.job.event.TaskAttemptEvent;
import org.apache.hadoop.mapreduce.v2.app.job.event.TaskAttemptEventType;
import org.apache.hadoop.mapreduce.v2.app.job.event.TaskAttemptStatusUpdateEvent;
import org.apache.hadoop.mapreduce.v2.app.job.event.TaskAttemptStatusUpdateEvent.TaskAttemptStatus;
import org.apache.hadoop.service.Service;
import org.apache.hadoop.test.GenericTestUtils;
import org.apache.hadoop.yarn.event.EventHandler;
import org.apache.hadoop.yarn.util.Clock;
import org.apache.hadoop.yarn.util.ControlledClock;
import org.apache.hadoop.yarn.util.SystemClock;
import org.junit.Rule;
import org.junit.Test;

import com.google.common.base.Supplier;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.model.Statement;

/**
 * The type Test speculative execution with mr app.
 * It test the speculation behavior given a list of estimator classes.
 */
@SuppressWarnings({ "unchecked", "rawtypes" })
@RunWith(Parameterized.class)
public class TestSpeculativeExecutionWithMRApp {
  /** Number of times to re-try the failing tests. */
  private static final int ASSERT_SPECULATIONS_COUNT_RETRIES = 3;
  private static final int NUM_MAPPERS = 5;
  private static final int NUM_REDUCERS = 0;

  /**
   * Speculation has non-deterministic behavior due to racing and timing. Use
   * retry to verify that junit tests can pass.
   */
  @Retention(RetentionPolicy.RUNTIME)
  public @interface Retry {}

  /**
   * The type Retry rule.
   */
  class RetryRule implements TestRule {

    private AtomicInteger retryCount;

    /**
     * Instantiates a new Retry rule.
     *
     * @param retries the retries
     */
    RetryRule(int retries) {
      super();
      this.retryCount = new AtomicInteger(retries);
    }

    @Override
    public Statement apply(final Statement base,
        final Description description) {
      return new Statement() {
        @Override
        public void evaluate() throws Throwable {
          Throwable caughtThrowable = null;

          while (retryCount.getAndDecrement() > 0) {
            try {
              base.evaluate();
              return;
            } catch (Throwable t) {
              if (retryCount.get() > 0 &&
                  description.getAnnotation(Retry.class) != null) {
                caughtThrowable = t;
                System.out.println(
                    description.getDisplayName() +
                        ": Failed, " +
                        retryCount.toString() +
                        " retries remain");
              } else {
                throw caughtThrowable;
              }
            }
          }
        }
      };
    }
  }

  /**
   * The Rule.
   */
  @Rule
  public RetryRule rule = new RetryRule(ASSERT_SPECULATIONS_COUNT_RETRIES);

  /**
   * Gets test parameters.
   *
   * @return the test parameters
   */
  @Parameterized.Parameters(name = "{index}: TaskEstimator(EstimatorClass {0})")
  public static Collection<Object[]> getTestParameters() {
    return Arrays.asList(new Object[][] {
        {SimpleExponentialTaskRuntimeEstimator.class},
        {LegacyTaskRuntimeEstimator.class}
    });
  }

  private Class<? extends TaskRuntimeEstimator> estimatorClass;

  /**
   * Instantiates a new Test speculative execution with mr app.
   *
   * @param estimatorKlass the estimator klass
   */
  public TestSpeculativeExecutionWithMRApp(
      Class<? extends TaskRuntimeEstimator>  estimatorKlass) {
    this.estimatorClass = estimatorKlass;
  }

  /**
   * Test speculate successful without update events.
   *
   * @throws Exception the exception
   */
  @Retry
  @Test (timeout = 360000)
  public void testSpeculateSuccessfulWithoutUpdateEvents() throws Exception {

    Clock actualClock = SystemClock.getInstance();
    final ControlledClock clock = new ControlledClock(actualClock);
    clock.setTime(System.currentTimeMillis());

    MRApp app =
        new MRApp(NUM_MAPPERS, NUM_REDUCERS, false, "test", true, clock);
    Job job = app.submit(createConfiguration(), true, true);
    app.waitForState(job, JobState.RUNNING);

    Map<TaskId, Task> tasks = job.getTasks();
    Assert.assertEquals("Num tasks is not correct", NUM_MAPPERS + NUM_REDUCERS,
      tasks.size());
    Iterator<Task> taskIter = tasks.values().iterator();
    while (taskIter.hasNext()) {
      app.waitForState(taskIter.next(), TaskState.RUNNING);
    }

    // Process the update events
    clock.setTime(System.currentTimeMillis() + 2000);
    EventHandler appEventHandler = app.getContext().getEventHandler();
    for (Map.Entry<TaskId, Task> mapTask : tasks.entrySet()) {
      for (Map.Entry<TaskAttemptId, TaskAttempt> taskAttempt : mapTask
        .getValue().getAttempts().entrySet()) {
        TaskAttemptStatus status =
            createTaskAttemptStatus(taskAttempt.getKey(), (float) 0.8,
              TaskAttemptState.RUNNING);
        TaskAttemptStatusUpdateEvent event =
            new TaskAttemptStatusUpdateEvent(taskAttempt.getKey(),
                new AtomicReference<>(status));
        appEventHandler.handle(event);
      }
    }

    Random generator = new Random();
    Object[] taskValues = tasks.values().toArray();
    final Task taskToBeSpeculated =
        (Task) taskValues[generator.nextInt(taskValues.length)];

    // Other than one random task, finish every other task.
    for (Map.Entry<TaskId, Task> mapTask : tasks.entrySet()) {
      for (Map.Entry<TaskAttemptId, TaskAttempt> taskAttempt : mapTask
        .getValue().getAttempts().entrySet()) {
        if (mapTask.getKey() != taskToBeSpeculated.getID()) {
          appEventHandler.handle(new TaskAttemptEvent(taskAttempt.getKey(),
            TaskAttemptEventType.TA_DONE));
          appEventHandler.handle(new TaskAttemptEvent(taskAttempt.getKey(),
            TaskAttemptEventType.TA_CONTAINER_COMPLETED));
          app.waitForState(taskAttempt.getValue(), TaskAttemptState.SUCCEEDED,
              TaskAttemptState.KILLED);
        }
      }
    }

    GenericTestUtils.waitFor(new Supplier<Boolean>() {
      @Override
      public Boolean get() {
        if (taskToBeSpeculated.getAttempts().size() != 2) {
          clock.setTime(System.currentTimeMillis() + 1000);
          return false;
        } else {
          return true;
        }
      }
    }, 1000, 60000);
    // finish 1st TA, 2nd will be killed
    TaskAttempt[] ta = makeFirstAttemptWin(appEventHandler, taskToBeSpeculated);
    verifySpeculationMessage(app, ta);
    app.waitForState(Service.STATE.STOPPED);
  }

  /**
   * Test speculate successful with update events.
   *
   * @throws Exception the exception
   */
  @Retry
  @Test (timeout = 360000)
  public void testSpeculateSuccessfulWithUpdateEvents() throws Exception {

    Clock actualClock = SystemClock.getInstance();
    final ControlledClock clock = new ControlledClock(actualClock);
    clock.setTime(System.currentTimeMillis());

    MRApp app =
        new MRApp(NUM_MAPPERS, NUM_REDUCERS, false, "test", true, clock);
    Job job = app.submit(createConfiguration(), true, true);
    app.waitForState(job, JobState.RUNNING);

    Map<TaskId, Task> tasks = job.getTasks();
    Assert.assertEquals("Num tasks is not correct", NUM_MAPPERS + NUM_REDUCERS,
      tasks.size());
    Iterator<Task> taskIter = tasks.values().iterator();
    while (taskIter.hasNext()) {
      app.waitForState(taskIter.next(), TaskState.RUNNING);
    }

    // Process the update events
    clock.setTime(System.currentTimeMillis() + 1000);
    EventHandler appEventHandler = app.getContext().getEventHandler();
    for (Map.Entry<TaskId, Task> mapTask : tasks.entrySet()) {
      for (Map.Entry<TaskAttemptId, TaskAttempt> taskAttempt : mapTask
        .getValue().getAttempts().entrySet()) {
        TaskAttemptStatus status =
            createTaskAttemptStatus(taskAttempt.getKey(), (float) 0.5,
              TaskAttemptState.RUNNING);
        TaskAttemptStatusUpdateEvent event =
            new TaskAttemptStatusUpdateEvent(taskAttempt.getKey(),
                new AtomicReference<>(status));
        appEventHandler.handle(event);
      }
    }

    Task speculatedTask = null;
    int numTasksToFinish = NUM_MAPPERS + NUM_REDUCERS - 1;
    clock.setTime(System.currentTimeMillis() + 1000);
    for (Map.Entry<TaskId, Task> task : tasks.entrySet()) {
      for (Map.Entry<TaskAttemptId, TaskAttempt> taskAttempt : task.getValue()
        .getAttempts().entrySet()) {
        if (numTasksToFinish > 0) {
          appEventHandler.handle(new TaskAttemptEvent(taskAttempt.getKey(),
            TaskAttemptEventType.TA_DONE));
          appEventHandler.handle(new TaskAttemptEvent(taskAttempt.getKey(),
            TaskAttemptEventType.TA_CONTAINER_COMPLETED));
          numTasksToFinish--;
          app.waitForState(taskAttempt.getValue(), TaskAttemptState.KILLED,
              TaskAttemptState.SUCCEEDED);
        } else {
          // The last task is chosen for speculation
          TaskAttemptStatus status =
              createTaskAttemptStatus(taskAttempt.getKey(), (float) 0.75,
                TaskAttemptState.RUNNING);
          speculatedTask = task.getValue();
          TaskAttemptStatusUpdateEvent event =
              new TaskAttemptStatusUpdateEvent(taskAttempt.getKey(),
                  new AtomicReference<>(status));
          appEventHandler.handle(event);
        }
      }
    }

    clock.setTime(System.currentTimeMillis() + 15000);

    for (Map.Entry<TaskId, Task> task : tasks.entrySet()) {
      for (Map.Entry<TaskAttemptId, TaskAttempt> taskAttempt : task.getValue()
        .getAttempts().entrySet()) {
        if (!(taskAttempt.getValue().getState() == TaskAttemptState.SUCCEEDED
            || taskAttempt.getValue().getState() == TaskAttemptState.KILLED)) {
          TaskAttemptStatus status =
              createTaskAttemptStatus(taskAttempt.getKey(), (float) 0.75,
                TaskAttemptState.RUNNING);
          TaskAttemptStatusUpdateEvent event =
              new TaskAttemptStatusUpdateEvent(taskAttempt.getKey(),
                  new AtomicReference<>(status));
          appEventHandler.handle(event);
        }
      }
    }

    final Task speculatedTaskConst = speculatedTask;
    GenericTestUtils.waitFor(new Supplier<Boolean>() {
      @Override
      public Boolean get() {
        if (speculatedTaskConst.getAttempts().size() != 2) {
          clock.setTime(System.currentTimeMillis() + 1000);
          return false;
        } else {
          return true;
        }
      }
    }, 1000, 60000);
    TaskAttempt[] ta = makeFirstAttemptWin(appEventHandler, speculatedTask);
    verifySpeculationMessage(app, ta);
    app.waitForState(Service.STATE.STOPPED);
  }

  private static TaskAttempt[] makeFirstAttemptWin(
      EventHandler appEventHandler, Task speculatedTask) {

    // finish 1st TA, 2nd will be killed
    Collection<TaskAttempt> attempts = speculatedTask.getAttempts().values();
    TaskAttempt[] ta = new TaskAttempt[attempts.size()];
    attempts.toArray(ta);
    appEventHandler.handle(
        new TaskAttemptEvent(ta[0].getID(), TaskAttemptEventType.TA_DONE));
    appEventHandler.handle(new TaskAttemptEvent(ta[0].getID(),
        TaskAttemptEventType.TA_CONTAINER_COMPLETED));
    return ta;
  }

  private static void verifySpeculationMessage(MRApp app, TaskAttempt[] ta)
      throws Exception {
    app.waitForState(ta[0], TaskAttemptState.SUCCEEDED);
    // The speculative attempt may be not killed before the MR job succeeds.
  }

  private TaskAttemptStatus createTaskAttemptStatus(TaskAttemptId id,
      float progress, TaskAttemptState state) {
    TaskAttemptStatus status = new TaskAttemptStatus();
    status.id = id;
    status.progress = progress;
    status.taskState = state;
    return status;
  }

  private Configuration createConfiguration() {
    Configuration conf = new Configuration();
    conf.setClass(MRJobConfig.MR_AM_TASK_ESTIMATOR,
        estimatorClass,
        TaskRuntimeEstimator.class);
    if (SimpleExponentialTaskRuntimeEstimator.class.equals(estimatorClass)) {
      // set configurations specific to SimpleExponential estimator
      conf.setInt(
          MRJobConfig.MR_AM_TASK_ESTIMATOR_SIMPLE_SMOOTH_SKIP_INITIALS, 1);
      conf.setLong(
          MRJobConfig.MR_AM_TASK_ESTIMATOR_SIMPLE_SMOOTH_LAMBDA_MS,
          1000L * 10);
    }
    return conf;
  }
}
