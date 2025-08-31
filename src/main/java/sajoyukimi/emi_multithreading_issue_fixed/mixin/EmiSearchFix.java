package sajoyukimi.emi_multithreading_issue_fixed.mixin;

import dev.emi.emi.search.EmiSearch;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import sajoyukimi.emi_multithreading_issue_fixed.AsyncSearcher;

@Mixin(EmiSearch.class)
public class EmiSearchFix {
    private static final AsyncSearcher searcher = new AsyncSearcher();
    /**
     * @author sajoyukimi
     * @reason Replaced search method to fix multithreading issues
     */
    @Overwrite(remap = false)
    public static void search(String query) {
        searcher.startSearch(query);
    }
}
