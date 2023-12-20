package cgeo.geocaching.utils.workertask;

import cgeo.geocaching.utils.AndroidRxUtils;
import cgeo.geocaching.utils.CommonUtils;

import android.annotation.TargetApi;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;

import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.subjects.PublishSubject;
import io.reactivex.rxjava3.subjects.Subject;

/** Utility class to execute asynchronous worker tasks in the context of an activity. */
@TargetApi(24)
public class WorkerTask<I, P, R>  {

    private final Object taskMutex = new Object();

    private Supplier<WorkerTaskLogic<I, P, R>> taskSupplier;

    private LifecycleOwner owner;

    private final AtomicBoolean propertyChangedAllowed = new AtomicBoolean(true);

    private Subject<WorkerTaskEvent<I, P, R>> taskEventData = PublishSubject.create();
    private final Queue<Consumer<WorkerTaskEvent<I, P, R>>> taskListeners = new ConcurrentLinkedQueue<>();
    private Disposable observerDisposable;

    @Nullable private Consumer<R> noOwnerAction;

    private WorkerTaskLogic<I, P, R> taskLogic;
    private AtomicBoolean cancelTrigger;
    private final AtomicBoolean runFlag = new AtomicBoolean(false);
    private final AtomicBoolean disposedFlag = new AtomicBoolean(false);

    private Scheduler taskScheduler = null; //lazy-initialized on first start
    private Scheduler listenerScheduler = null; //lazy-initialized on first start

    public enum WorkerTaskEventType {
        STARTED, PROGRESS, FINISHED, CANCELLED
    }


    public static class WorkerTaskEvent<I, P, R> {
        public final WorkerTaskLogic<I, P, R> task;
        public final WorkerTask.WorkerTaskEventType type;
        public final I input;
        public final P progress;
        public final R result;

        WorkerTaskEvent(final WorkerTaskLogic<I, P, R> task, final WorkerTask.WorkerTaskEventType type, final I input, final P progress, final R result) {
            this.task = task;
            this.type = type;
            this.input = input;
            this.progress = progress;
            this.result = result;
        }

        @NonNull
        @Override
        public String toString() {
            return type + (input == null ? "" : ":I=" + input) + (progress == null ? "" : ":P=" + progress) + (result == null ? "" : ":R=" + result);
        }

    }

    public interface TaskFeature<I, P, R> {

        void accept(WorkerTask<? extends I, ? extends P, ? extends R> task);

    }

    private WorkerTask(@Nullable final LifecycleOwner owner, @NonNull final Supplier<WorkerTaskLogic<I, P, R>> taskSupplier) {
        this.owner = owner;
        this.taskSupplier = Objects.requireNonNull(taskSupplier);
        this.observerDisposable = taskEventData.subscribe(this::forwardEventToListeners);

        checkNoLifecycleReferences(this.taskSupplier);

        //auto-dispose on owner destroy
        if (this.owner != null) {
            this.owner.getLifecycle().addObserver((LifecycleEventObserver) (source, event) -> {
                if (event.getTargetState() == Lifecycle.State.DESTROYED) {
                    this.dispose();
                }
            });
        }
    }

    private void forwardEventToListeners(@NonNull final WorkerTaskEvent<I, P, R> event) {
        this.listenerScheduler.createWorker().schedule(() -> {
            for (Consumer<WorkerTaskEvent<I, P, R>> listener : this.taskListeners) {
                listener.accept(event);
            }
        });
    }

    private void postEvent(@NonNull final WorkerTaskEvent<I, P, R> event) {
        synchronized (taskMutex) {
            if (!disposedFlag.get()) {
                taskEventData.onNext(event);
            }
        }
    }

    /** Creates task configuration with given global id and task logic supplier */
    public static <I, P, R> WorkerTask<I, P, R> of(@Nullable final LifecycleOwner owner, @NonNull final Supplier<WorkerTaskLogic<I, P, R>> taskSupplier) {
        return of(owner, taskSupplier, null);
    }

    public static <I, P, R> WorkerTask<I, P, R> of(@Nullable final LifecycleOwner owner, @NonNull final Supplier<WorkerTaskLogic<I, P, R>> taskSupplier, final Consumer<R> resultListener) {
        final WorkerTask<I, P, R> task = new WorkerTask<>(owner, taskSupplier);
        task.addResultListener(resultListener);
        return task;
    }

    /** Adds a generic listener to task lifecycle events */
    public WorkerTask<I, P, R> addTaskListener(final WorkerTaskEventType eventType, final Consumer<WorkerTaskEvent<I, P, R>> taskListener) {
        synchronized (taskMutex) {
            checkChangeAllowed();
            checkOnlyOwnerLifecycleReferences(taskListener);
            if (taskListener != null) {
                if (eventType == null) {
                    this.taskListeners.add(taskListener);
                } else {
                    this.taskListeners.add(event -> {
                        if (event.type == eventType) {
                            taskListener.accept(event);
                        }
                    });
                }
            }
            return this;
        }
    }

    /** Adds a generic listener to task lifecycle events */
    public WorkerTask<I, P, R> addTaskListener(final Consumer<WorkerTaskEvent<I, P, R>> taskListener) {
        return addTaskListener(null, taskListener);
    }

    /** adds a listener for task progress to this task. Consumer will be executed on UI thread */
    public WorkerTask<I, P, R> addProgressListener(final Consumer<P> consumer) {
        if (consumer != null) {
            addTaskListener(WorkerTaskEventType.PROGRESS, event -> consumer.accept(event.progress));
        }
        return this;
    }

    /** adds a listener for task result to this task. Consumer will be executed on UI thread */
    public WorkerTask<I, P, R> addResultListener(final Consumer<R> consumer) {
        if (consumer != null) {
            addTaskListener(WorkerTaskEventType.FINISHED, event -> consumer.accept(event.result));
        }
        return this;
    }

    /** Sets the scheduler on which the background task is run. Default is {@link AndroidRxUtils#networkScheduler} */
    public WorkerTask<I, P, R> setTaskScheduler(final Scheduler taskScheduler) {
        synchronized (taskMutex) {
            checkChangeAllowed();
            checkNoLifecycleReferences(taskScheduler);
            this.taskScheduler = taskScheduler;
            return this;
        }
    }

    /** Sets the scheduler on which listeners are executed. Defaults to Android Main/UI Thread */
    public WorkerTask<I, P, R> setListenerScheduler(final Scheduler listenerScheduler) {
        synchronized (taskMutex) {
            checkChangeAllowed();
            checkNoLifecycleReferences(listenerScheduler);
            this.listenerScheduler = listenerScheduler;
            return this;
        }
    }

    /** Sets action to be executed if at time of finishing no observer is connected to task. Will be executed on taskScheduler! */
    public WorkerTask<I, P, R> setNoOwnerAction(final Consumer<R> noOwnerAction) {
        synchronized (taskMutex) {
            checkChangeAllowed();
            checkNoLifecycleReferences(noOwnerAction);
            this.noOwnerAction = noOwnerAction;
            return this;
        }
    }

    public WorkerTask<I, P, R> addFeature(final WorkerTask.TaskFeature<? super I, ? super P, ? super R> feature) {
        synchronized (taskMutex) {
            checkChangeAllowed();
            feature.accept(this);
            return this;
        }
    }

    /** starts the task. If task is currently running, then run is cancelled */
    public boolean start(final I input) {
        synchronized (taskMutex) {
            if (isRunning()) {
                cancel();
            }
            return startIfNotRunning(input);
        }
    }

    public  boolean startIfNotRunning(final I input) {
        synchronized (taskMutex) {

            checkActionAllowed();
            //check validity of configuration. If this fails, it is a programming error
            if (owner != null && owner.getLifecycle().getCurrentState() == Lifecycle.State.DESTROYED) {
                throw new IllegalStateException("Can't create task in state DESTROYED: " + owner);
            }

            //from now on, no more property changes...
            propertyChangedAllowed.set(false);

            //check if running
            if (runFlag.get()) {
                return false;
            }
            runFlag.set(true);

            final WorkerTaskLogic<I, P, R> lTaskLogic = taskSupplier.get();
            checkNoLifecycleReferences(lTaskLogic);

            final AtomicBoolean lCancelTrigger = new AtomicBoolean(false);
            final Consumer<R> lNoOwnerAction = this.noOwnerAction;
            this.taskLogic = lTaskLogic;
            this.cancelTrigger = lCancelTrigger;

            if (taskScheduler == null) {
                taskScheduler = AndroidRxUtils.networkScheduler;
            }
            if (listenerScheduler == null) {
                listenerScheduler = AndroidRxUtils.mainThreadScheduler;
            }

            postEvent(new WorkerTaskEvent<>(taskLogic, WorkerTaskEventType.STARTED, input, null, null));
            taskScheduler.createWorker().schedule(() ->
                runAsyncTask(lTaskLogic, lCancelTrigger, taskMutex, runFlag, disposedFlag, this::postEvent, lNoOwnerAction, input));

            return true;
        }
    }

    public boolean isRunning() {
        return runFlag.get();
    }

    public boolean isDisposed() {
        return disposedFlag.get();
    }

    public boolean cancel() {
        synchronized (taskMutex) {

            checkActionAllowed();

            if (!runFlag.get()) {
                return false;
            }
            runFlag.set(false);
            cancelTrigger.set(true);
            cancelTrigger = null;

            postEvent(new WorkerTaskEvent<>(this.taskLogic, WorkerTaskEventType.CANCELLED, null, null, null));
            this.taskLogic = null;

            return true;
        }
    }

    public void dispose() {
        dispose(true);
    }

    public void dispose(final boolean cancelTask) {
        synchronized (taskMutex) {

            if (cancelTask) {
                this.cancel();
            }

            if (disposedFlag.get()) {
                return;
            }

            disposedFlag.set(true);

            //release all resources
            this.observerDisposable.dispose();

            this.observerDisposable = null;
            this.taskEventData = null;
            this.taskListeners.clear();
            this.taskSupplier = null;
            this.owner = null;
            this.noOwnerAction = null;
            this.taskScheduler = null;
            this.listenerScheduler = null;
            this.taskLogic = null;
        }
    }

    /** Runs the actual async task. It runs on the taskListenerScheduler (asynchronous to listeners and this classes' calls) and thus must be self-sustainable */
    @WorkerThread
    private static <I, P, R> void runAsyncTask(
        final WorkerTaskLogic<I, P, R> task,
        final AtomicBoolean cancelTrigger,
        final Object taskMutex,
        final AtomicBoolean runFlag,
        final AtomicBoolean disposedFlag,
        final Consumer<WorkerTaskEvent<I, P, R>> eventPoster,
        final Consumer<R> noObserverAction,
        final I input) {

        //trigger the actual worker thread run
        final R result = task.run(input, p -> {
            synchronized (taskMutex) {
                if (!cancelTrigger.get()) {
                    eventPoster.accept(new WorkerTaskEvent<>(task, WorkerTaskEventType.PROGRESS, null, p, null));
                }
            }
        }, cancelTrigger::get);

        //run ended
        synchronized (taskMutex) {
            final boolean wasCancelled = cancelTrigger.get();
            if (!wasCancelled) {
                //end the run
                runFlag.set(false);
                //post results
                eventPoster.accept(new WorkerTaskEvent<>(task, WorkerTaskEventType.FINISHED, null, null, result));
                if (disposedFlag.get() && noObserverAction != null) {
                    noObserverAction.accept(result);
                }
            }
        }
    }

    private void checkChangeAllowed() {
        if (!propertyChangedAllowed.get()) {
            throw new IllegalStateException("Changes to properties now allowed after first task start");
        }
    }

    private void checkActionAllowed() {
        if (disposedFlag.get()) {
            throw new IllegalStateException("Task was already destroyed");
        }
    }

    private void checkNoLifecycleReferences(@Nullable final Object obj) {
        checkNoUnallowedReferences(obj, null);
    }

    private void checkOnlyOwnerLifecycleReferences(@Nullable final Object obj) {
        checkNoUnallowedReferences(obj, this.owner);
    }

    private static void checkNoUnallowedReferences(@Nullable final Object obj, @Nullable final LifecycleOwner allowedLifecycle) {
        if (obj == null) {
            return;
        }
        final Set<Class<? extends LifecycleOwner>> lifecycleClasses = CommonUtils.getReferencedClasses(obj, LifecycleOwner.class);
        if (lifecycleClasses.isEmpty()) {
            return;
        }
        if (lifecycleClasses.size() == 1 && allowedLifecycle != null && lifecycleClasses.contains(allowedLifecycle.getClass())) {
            return;
        }

        throw new IllegalStateException("Class '" + obj.getClass() + "' contains back-reference to LifecycleOwner(s) '" + lifecycleClasses + "'" +
            "This is not allowed because it would produce memory leaks!");
    }


}
