package app.navelo.fixture;
import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.provider.DocumentsContract;
import android.widget.*;

public class FolderPicker extends Activity {
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(40,80,40,40);
        TextView title = new TextView(this); title.setTextSize(26); title.setText("Navelo acceptance-test folder"); layout.addView(title);
        TextView body = new TextView(this); body.setTextSize(18); body.setText("SDK sample video, two episodes, and English subtitles. This test-only picker grants real Android document-tree access."); layout.addView(body);
        Button choose = new Button(this); choose.setText("Use test media folder"); layout.addView(choose);
        choose.setOnClickListener(view -> {
            Intent result = new Intent().setData(DocumentsContract.buildTreeDocumentUri("app.navelo.fixture.documents", "media"));
            result.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            setResult(RESULT_OK, result); finish();
        });
        setContentView(layout);
    }
}
