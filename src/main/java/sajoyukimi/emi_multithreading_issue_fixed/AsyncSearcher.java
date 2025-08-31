package sajoyukimi.emi_multithreading_issue_fixed;

import com.google.common.collect.Lists;
import java.lang.Thread;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.search.EmiSearch;
import dev.emi.emi.runtime.EmiLog;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.screen.EmiScreenManager;

public class AsyncSearcher {
    private final AtomicReference<Thread> runningSearchThread = new AtomicReference<>();

    public void startSearch(String query) {
        SearchWorker worker = new SearchWorker(this, query, EmiScreenManager.getSearchSource());
        // 创建虚拟线程，但先不启动
        Thread newSearchThread = Thread.ofVirtual().unstarted(worker);

        // 获取当前正在运行的旧线程，并用新线程替换它
        Thread oldSearchThread = runningSearchThread.getAndSet(newSearchThread);

        // 如果之前有一个搜索正在运行，就中断它
        if (oldSearchThread != null) {
            oldSearchThread.interrupt();
            try{
                oldSearchThread.join();//等待线程退出
            } catch (InterruptedException e) {
                EmiLog.error("Error while trying to wait for old thread to stop:", e);
            }
        }
        // 启动新的搜索线程
        EmiSearch.searchThread = newSearchThread;
        newSearchThread.start();
    }

    private static class SearchWorker implements Runnable {
        private final String query;
        private final List<? extends EmiIngredient> source;
        private final AsyncSearcher src;
        public SearchWorker(AsyncSearcher InputSrc, String query, List<? extends EmiIngredient> source) {
            src = InputSrc;
            this.query = query;
            this.source = source;
        }

        @Override
        public void run() {
            List<EmiIngredient> stacks = null;
            try {
                EmiSearch.CompiledQuery compiled = new EmiSearch.CompiledQuery(query);
                if (Thread.currentThread().isInterrupted()){
                    return;
                }
                EmiSearch.compiledQuery = compiled;
                if (compiled.isEmpty()) {
                    return;
                }
                stacks = Lists.newArrayList();
                for (EmiIngredient stack : source) {
                    if (Thread.currentThread().isInterrupted()) {//如果需要被中断了，则退出
                        return;
                    }
                    List<EmiStack> ess = stack.getEmiStacks();
                    if (ess.size() == 1) {
                        EmiStack es = ess.get(0);
                        if (compiled.test(es)) {
                            stacks.add(stack);
                        }
                    }
                }
            } catch (Exception e) {
                EmiLog.error("Error when attempting to search:", e);
            } finally {
                // 任务结束后，将自己从runningSearchThread中移除
                // 使用compareAndSet确保只有当前线程自己才能移除自己，防止并发问题
                if (src.runningSearchThread.compareAndSet(Thread.currentThread(), null)) {
                    //如果行为成功，则代表是完成的任务线程，开始执行剩下的操作
                    if (stacks == null){
                        EmiSearch.stacks = List.copyOf(source);
                    }
                    else{
                        EmiSearch.stacks = List.copyOf(stacks);
                    }
                    EmiSearch.searchThread = null;
                }
            }
        }
    }
}
