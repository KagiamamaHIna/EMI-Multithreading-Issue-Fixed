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

        synchronized(AsyncSearcher.class){
            // 获取当前正在运行的旧线程，并用新线程替换它
            Thread oldSearchThread = runningSearchThread.getAndSet(newSearchThread);

            // 如果之前有一个搜索正在运行，就中断它
            if (oldSearchThread != null) {
                oldSearchThread.interrupt();
                worker.SetOldThread(oldSearchThread);
            }
            // 启动新的搜索线程
            EmiSearch.searchThread = newSearchThread;
            newSearchThread.start();
        }
    }

    private static class SearchWorker implements Runnable {
        private final String query;
        private final List<? extends EmiIngredient> source;
        private final AsyncSearcher src;
        private Thread OldThread = null;
        public SearchWorker(AsyncSearcher InputSrc, String query, List<? extends EmiIngredient> source) {
            src = InputSrc;
            this.query = query;
            this.source = source;
        }

        void SetOldThread(Thread old){
            OldThread = old;
        }

        private void apply(List<? extends EmiIngredient> result){
            synchronized(AsyncSearcher.class){
                if (src.runningSearchThread.compareAndSet(Thread.currentThread(), null)){
                    EmiSearch.stacks = result;
                    EmiSearch.searchThread = null;
                }
            }
        }

        @Override
        public void run() {
            List<EmiIngredient> stacks = null;
            try {
                if (OldThread != null){
                    try{
                        OldThread.join();//等待旧线程结束
                    } catch (InterruptedException e) {
                        OldThread.join();//只会被通知一次interrupt，所以捕获后重新等待即可
                    }
                }
                if (Thread.currentThread().isInterrupted()){//如果我也过期了就退出
                    return;
                }
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
                if (!Thread.currentThread().isInterrupted()){
                    List<? extends EmiIngredient> result;
                    if (stacks == null){
                        result = List.copyOf(source);
                    }
                    else{
                        result = List.copyOf(stacks);
                    }
                    apply(result);
                }
            }
        }
    }
}
