/*
 * Copyright (c) 2012, the authors.
 *
 *   This file is part of 'Nextflow'.
 *
 *   Nextflow is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   Nextflow is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with Nextflow.  If not, see <http://www.gnu.org/licenses/>.
 */

package nextflow.processor

import groovy.util.logging.Slf4j
import nextflow.Session
import nextflow.executor.AbstractExecutor

/**
 * Monitor tasks execution for completion notifying their results
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
class TaskDispatcher {

    /**
     * The current session object
     */
    final private Session session

    /**
     * Map each executor class with its tasks monitor, in other words there's one {@code TaskMonitor}
     * instance for each type of executor
     */
    final private Map<Class<? extends AbstractExecutor>, TaskMonitor> monitors = [:]

    private volatile boolean started

    /**
     * Dispatcher constructor
     *
     * @param session
     */
    TaskDispatcher( Session session ) {

        this.session = session

    }

    /**
     *
     * Get an instance of {@code TaskMonitor} in the {@code #monitors} map,
     * when the map contains no value for the specified executor invoke the
     * closure in order to create a new {@code TaskMonitor} object,
     * add it to the map and return it.
     *
     * NOTE: this method is not thread-safe by design since it is invoked
     * only by the script interpreter.
     *
     * @param type
     * @param create
     * @return
     */
    TaskMonitor getOrCreateMonitor( Class<? extends AbstractExecutor> type, Closure<TaskMonitor> create ) {

        if( monitors.containsKey(type) ) {
            return monitors.get(type)
        }

        def result = create.call()
        monitors.put(type,result)

        if( started ) {
            log.debug "Starting monitor: ${result.class.simpleName}"
            result.start()
        }

        return result
    }

    void start() {
        log.debug "Dispatcher > start"
        started = true
    }


    /**
     * Submit the specified task for execution to the underlying system
     * and add it to the queue of tasks to be monitored.
     *
     * @param task A {@code TaskRun} instance
     */
    void submit( TaskRun task, boolean blocking ) {
        log.trace "Scheduling process: ${task}"

        if( session.isTerminated() ) {
            new IllegalStateException("Session terminated - Cannot add process to execution queue: ${task}")
        }

        /*
         * Add the task to the queue for processing
         * Note: queue is implemented as a fixed size blocking queue, when
         * there's not space *put* operation will block until, some other tasks finish
         */
        def handler = task.processor.executor.submitTask(task, blocking)
        if( handler && blocking ) {
            log.trace "Process ${task} > blocking"
            handler.latch.await()
            log.trace "Process ${task} > complete"
        }
    }


    /**
     * Notify task start event
     */
    public void notifyStarted(TaskHandler handler) {

    }

    /**
     * Notify task termination event
     *
     * @param handler
     */
    public void notifyTerminated(TaskHandler handler) {

        // finalize the tasks execution
        handler.task.processor.finalizeTask(handler.task)
        // trigger the count down latch when it is a blocking task
        handler.latch?.countDown()

    }

    /**
     * Notify a task failure
     *
     * @param handler
     * @param e
     */
    public void notifyError(Throwable e, TaskHandler handler = null ) {

        handler.task.processor.handleException(e, handler?.task)

    }


}

