package cgeo.geocaching.wherigo;

import cgeo.geocaching.R;
import cgeo.geocaching.databinding.WherigoCartridgeDetailsBinding;
import cgeo.geocaching.location.Geopoint;
import cgeo.geocaching.ui.SimpleItemListModel;
import cgeo.geocaching.ui.TextParam;
import cgeo.geocaching.ui.dialog.SimpleDialog;
import cgeo.geocaching.utils.Formatter;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.view.LayoutInflater;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import cz.matejcik.openwig.formats.CartridgeFile;

public class WherigoCartridgeDialogProvider implements IWherigoDialogProvider {

    private final WherigoCartridgeInfo cartridgeInfo;
    private final CartridgeFile cartridgeFile;

    public WherigoCartridgeDialogProvider(final WherigoCartridgeInfo cartridgeInfo) {
        this.cartridgeInfo = cartridgeInfo;
        this.cartridgeFile = cartridgeInfo.getCartridgeFile();
    }

    @Override
    public Dialog createDialog(final Activity activity) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity, R.style.cgeo_fullScreenDialog);
        final WherigoCartridgeDetailsBinding binding = WherigoCartridgeDetailsBinding.inflate(LayoutInflater.from(activity));
        final AlertDialog dialog = builder.create();
        dialog.setTitle(cartridgeFile.name);
        dialog.setView(binding.getRoot());

        final Map<String, Date> saveGames = cartridgeInfo.getSaveGames();

        binding.description.setText(WherigoGame.get().toDisplayText(cartridgeFile.description));
        TextParam.text("Debug Information:\n" +
            "- **CGUID:** " + cartridgeInfo.getCGuid() + "\n" +
            "- **Author:** " + cartridgeFile.author + "\n" +
            "- **Location:** " + new Geopoint(cartridgeFile.latitude, cartridgeFile.longitude) + "\n" +
            "- **Device:** " + cartridgeFile.device + "\n" +
            "- **Type:** " + cartridgeFile.type + "\n" +
            "- **Member:** " + cartridgeFile.member + "\n" +
            "- **Version:** " + cartridgeFile.version + "\n" +
            "- **Save Games** " + saveGames.size() + " (" + saveGames + ")\n" +
            "- **C:** " + cartridgeFile.code + "\n" +
            "- **Url:** " + cartridgeFile.url + "\n").setMarkdown(true).applyTo(binding.debugInfo);
            binding.debugInfo.setVisibility(WherigoGame.get().isDebugMode() ? View.VISIBLE : View.GONE);

        binding.media.setMediaData("jpg", cartridgeInfo.getSplashData(), null);

        final List<String> actions = new ArrayList<>();
        actions.add("New Game");
        if (!saveGames.isEmpty()) {
            actions.add("Load Game");
        }
        actions.add("Close");

        WherigoUtils.setViewActions(actions, binding.dialogActionlist, TextParam::text, item -> {
            WherigoDialogManager.get().clear();
            if (item.equals("Close")) {
                return;
            }
            //"New Game" or "Load Game" requires ending a running game
            if (WherigoGame.get().isPlaying()) {
                SimpleDialog.of(activity).setTitle(TextParam.text("Wherigo Game running"))
                    .setMessage(TextParam.text("A Wherigo Game is currently running for cartridge '" + WherigoGame.get().getCartridgeInfo().getCartridgeFile().name +
                        "'. Do you want to end this game?")).confirm(() -> {
                            final int[] listenerId = new int[1];
                            listenerId[0] = WherigoGame.get().addListener(notifyType -> {
                                if (notifyType.equals(WherigoGame.NotifyType.END)) {
                                    startAnotherGame(item.equals("Load Game"), activity, saveGames);
                                    WherigoGame.get().removeListener(listenerId[0]);
                                }
                            });
                            WherigoGame.get().stopGame();
                    });
        } else {
                startAnotherGame(item.equals("Load Game"), activity, saveGames);
            }
        });

        return dialog;
    }

    @Override
    public void onGameNotification(final WherigoGame.NotifyType notifyType) {
        //do nothing
    }

    private void startAnotherGame(final boolean loadGame, final Activity activity, final Map<String, Date> saveGames) {
        if (loadGame) {
            chooseSavefile(activity, saveGames);
        } else {
            WherigoGame.get().newGame(cartridgeInfo.getFileInfo());
        }
    }

    private void chooseSavefile(final Activity activity, final Map<String, Date> saveGames) {

        final List<String> saveGameList = new ArrayList<>(saveGames.keySet());
        Collections.sort(saveGameList);
        saveGameList.add(0, null);

        final SimpleDialog.ItemSelectModel<String> model = new SimpleDialog.ItemSelectModel<>();
        model
                .setItems(saveGameList)
                .setDisplayMapper(s ->  TextParam.text(s == null ? "<New Game>" : s +
                        " (" + (saveGames.get(s) == null ? "-" : Formatter.formatDateForFilename(Objects.requireNonNull(saveGames.get(s)).getTime())) + ")"))
                .setChoiceMode(SimpleItemListModel.ChoiceMode.SINGLE_PLAIN);

        SimpleDialog.of(activity)
                .setTitle(TextParam.text("Choose a Savegame to load"))
                .selectSingle(model, s -> {
                    WherigoGame.get().loadGame(cartridgeInfo.getFileInfo(), s);
                });
    }

}
