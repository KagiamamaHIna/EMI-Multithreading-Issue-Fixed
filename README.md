# EMI Multithreading Issue-Fixed
练手作，主要是尝试修复在jech和emi同时存在的时候，因为线程不安全导致的搜索时潜在发生的NPE问题

使用Java21开发，模组支持的是1.20.1forge

## 问题解析

NPE问题原理大致如下，参考这篇[报错日志](https://github.com/Towdium/JustEnoughCharacters/issues/146#issuecomment-2907756948)：

注意到这一行：

at dev.emi.emi.search.EmiSearch$**CompiledQuery**.lambda$new$0(EmiSearch.java:208) ~[emi%20配方查看%201.1.22+1.20.1+forge%20测试.jar%23582!/:?]

这里指向是CompiledQuery的构造方法里的一段代码，调用方在[EmiSearch.SearchWorker.run:264](https://github.com/emilyploszaj/emi/blob/0be856e06f84ccab659e3d1369ad03c899491281/xplat/src/main/java/dev/emi/emi/search/EmiSearch.java#L264):
```java
@Override
public void run() {
    try {
        CompiledQuery compiled = new CompiledQuery(query);//:264
        compiledQuery = compiled;
        //...
    }
}
```

可以看得出来，问题出自查询结果时，这是由什么导致的呢？

看EmiSearch.search方法：
```java
public static void search(String query) {
	synchronized (EmiSearch.class) {
		SearchWorker worker = new SearchWorker(query, EmiScreenManager.getSearchSource());
		currentWorker = worker;
		
		searchThread = new Thread(worker);
		searchThread.setDaemon(true);
		searchThread.start();
	}
}
```

这里的实现是直接新建一个新的线程并启动，然后通过currentWorker通知其他正在运行的线程要退出了

发现什么问题了吗？他不会强制等待旧线程的退出，旧线程是通过这样的代码实现退出的：
```java
int processed = 0;
for (EmiIngredient stack : source) {
	if (processed++ >= 1024) {
        processed = 0;
        if (this != currentWorker) {
            return;
        }
    }
    //...
}
```
通过累计processed，在处理到指定次数1024的时候，检查自己是否过期

尽管问题并不是出在统计结果这里，但不合适的退出代码不仅会浪费额外的资源，并且因为没有等待旧线程退出且假设了CompiledQuery构造时一定是线程安全的，导致了Java PinIn内部出现了数据竞争问题

这里我们就可以得出结论了：问题的来源是有可能在短时间内，启动多个线程同时调用了CompiledQuery构造方法，这导致了Java PinIn内部出现了数据竞争问题，进而产生问题

## 本项目的解决方案
问题已经查明，那么思路就很清晰了

本项目是通过虚拟线程+协作式退出实现的，通过join强制等待旧线程退出，保证始终只有一个线程可以进行搜索，理论上能修复这个问题，如果我的分析无误

代码实现在[AsyncSearcher.java](/src/main/java/sajoyukimi/emi_multithreading_issue_fixed/AsyncSearcher.java)里面
