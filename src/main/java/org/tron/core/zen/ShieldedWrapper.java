package org.tron.core.zen;

import com.google.protobuf.ByteString;
import io.netty.util.internal.StringUtil;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.Getter;
import lombok.Setter;
import org.tron.api.GrpcAPI.DecryptNotes;
import org.tron.api.GrpcAPI.DecryptNotes.NoteTx;
import org.tron.api.GrpcAPI.IvkDecryptParameters;
import org.tron.api.GrpcAPI.Note;
import org.tron.api.GrpcAPI.NoteParameters;
import org.tron.api.GrpcAPI.SpendResult;
import org.tron.common.utils.ByteArray;
import org.tron.protos.Protocol.Block;
import org.tron.walletserver.WalletApi;

public class ShieldedWrapper {

  private final static String PREFIX_FOLDER = "WalletShielded";
  private final static String IVK_AND_NUM_FILE_NAME = PREFIX_FOLDER + "/scanblocknumber";
  private final static String UNSPEND_NOTE_FILE_NAME = PREFIX_FOLDER + "/unspendnote";
  private final static String SPEND_NOTE_FILE_NAME = PREFIX_FOLDER + "/spendnote";
  private final static String SHIELDED_ADDRESS_FILE_NAME = PREFIX_FOLDER + "/shieldedaddress";

  private WalletApi wallet;
  private static AtomicLong nodeIndex = new AtomicLong(0L);
  private Thread thread;

  @Setter
  @Getter
  Map<String, ShieldedAddressInfo> shieldedAddressInfoMap = new ConcurrentHashMap();
  @Setter
  private boolean resetNote = false;
  @Getter
  @Setter
  public Map<String, Long> ivkMapScanBlockNum = new ConcurrentHashMap();
  @Getter
  @Setter
  public Map<Long, ShieldedNoteInfo>  utxoMapNote = new ConcurrentHashMap();
  @Getter
  @Setter
  public List<ShieldedNoteInfo> spendUtxoList = new ArrayList<>();

  public void setWallet(WalletApi walletApi) {
    wallet = walletApi;
    if (!thread.isAlive()) {
      thread.start();
    }
  }

  public class scanIvkRunable implements Runnable {
    public void run(){
      int count = 24;
      for (;;) {
        try {
          scanBlockByIvk();
          updateNoteWhetherSpend();
        } catch (Exception e) {
          ++count;
          if (count >= 24) {
            if (e.getMessage() != null) {
              System.out.println(e.getMessage());
            }
            System.out.println("Please user command resetshieldednote to reset notes!!");
            count = 0;
          }
        } finally {
          try {
            //wait for 2.5 seconds
            for (int i=0; i<5; ++i) {
              Thread.sleep(500);
              if (resetNote) {
                resetShieldedNote();
                resetNote = false;
                count = 0;
                System.out.println("Reset shielded note success!");
              }
            }
          } catch ( Exception e) {
          }
        }
      }
    }
  }

  private void resetShieldedNote() {
    ivkMapScanBlockNum.clear();
    for (Entry<String, ShieldedAddressInfo> entry : getShieldedAddressInfoMap().entrySet() ) {
      ivkMapScanBlockNum.put(ByteArray.toHexString(entry.getValue().getIvk()), 0L);
    }

    utxoMapNote.clear();
    spendUtxoList.clear();

    ZenUtils.clearFile(IVK_AND_NUM_FILE_NAME);
    ZenUtils.clearFile(UNSPEND_NOTE_FILE_NAME);
    ZenUtils.clearFile(SPEND_NOTE_FILE_NAME);
    nodeIndex.set(0L);

    updateIvkAndBlockNumFile();
  }

  private void scanBlockByIvk() {
    Block block = wallet.getBlock(-1);
    if (block != null) {
      long blockNum = block.getBlockHeader().toBuilder().getRawData().getNumber();
      for (Entry<String, Long> entry : ivkMapScanBlockNum.entrySet()) {
        long start = entry.getValue();
        long end = start;
        while (end < blockNum) {
          if (blockNum - start > 1000) {
            end = start + 1000;
          } else {
            end = blockNum;
          }

          IvkDecryptParameters.Builder builder = IvkDecryptParameters.newBuilder();
          builder.setStartBlockIndex(start);
          builder.setEndBlockIndex(end);
          builder.setIvk(ByteString.copyFrom(ByteArray.fromHexString(entry.getKey())));
          Optional<DecryptNotes> notes = wallet.scanNoteByIvk(builder.build(), false);
          if (notes.isPresent()) {
            for (int i = 0; i < notes.get().getNoteTxsList().size(); ++i) {
              NoteTx noteTx = notes.get().getNoteTxsList().get(i);
              ShieldedNoteInfo noteInfo = new ShieldedNoteInfo();
              noteInfo.setPaymentAddress(noteTx.getNote().getPaymentAddress());
              noteInfo.setR(noteTx.getNote().getRcm().toByteArray());
              noteInfo.setValue(noteTx.getNote().getValue());
              noteInfo.setTrxId(ByteArray.toHexString(noteTx.getTxid().toByteArray()));
              noteInfo.setIndex(noteTx.getIndex());
              noteInfo.setNoteIndex(nodeIndex.getAndIncrement());
              noteInfo.setMemo(noteTx.getNote().getMemo().toByteArray());

              utxoMapNote.put(noteInfo.getNoteIndex(), noteInfo);
            }
            saveUnspendNoteToFile();
          }
          start = end;
        }
        ivkMapScanBlockNum.put(entry.getKey(), blockNum);
      }
      updateIvkAndBlockNumFile();
    }
  }

  private void updateNoteWhetherSpend() throws Exception {
    for (Entry<Long, ShieldedNoteInfo> entry : utxoMapNote.entrySet()) {
      ShieldedNoteInfo noteInfo = entry.getValue();

      ShieldedAddressInfo addressInfo = getShieldedAddressInfoMap().get(noteInfo.getPaymentAddress());
      NoteParameters.Builder builder = NoteParameters.newBuilder();
      builder.setAk(ByteString.copyFrom(addressInfo.getFullViewingKey().getAk()));
      builder.setNk(ByteString.copyFrom(addressInfo.getFullViewingKey().getNk()));

      Note.Builder noteBuild = Note.newBuilder();
      noteBuild.setPaymentAddress(noteInfo.getPaymentAddress());
      noteBuild.setValue(noteInfo.getValue());
      noteBuild.setRcm(ByteString.copyFrom(noteInfo.getR()));
      noteBuild.setMemo(ByteString.copyFrom(noteInfo.getMemo()));
      builder.setNote(noteBuild.build());
      builder.setTxid(ByteString.copyFrom(ByteArray.fromHexString(noteInfo.getTrxId())));
      builder.setIndex(noteInfo.getIndex());

      Optional<SpendResult> result = wallet.isNoteSpend(builder.build(), false);
      if (result.isPresent() && result.get().getResult()) {
        spendNote(entry.getKey());
      }
    }
  }

  public boolean Init() {
    ZenUtils.checkFolderExist(PREFIX_FOLDER);

    loadAddressFromFile();
    loadIvkFromFile();
    loadUnSpendNoteFromFile();
    loadSpendNoteFromFile();

    thread = new Thread(new scanIvkRunable());
    return true;
  }

  /**
   * set some index note is spend
   * @param noteIndex
   * @return
   */
  public boolean spendNote(long noteIndex ) {
    ShieldedNoteInfo noteInfo = utxoMapNote.get(noteIndex);
    if (noteInfo != null) {
      utxoMapNote.remove(noteIndex);
      spendUtxoList.add(noteInfo);

      saveUnspendNoteToFile();
      saveSpendNoteToFile(noteInfo);
    } else {
      System.err.println("Find note failure. index:" + noteIndex);
    }
    return true;
  }

  /**
   * save new shielded address and scan block num
   * @param addressInfo  new shielded address
   * @return
   */
  public boolean addNewShieldedAddress(final ShieldedAddressInfo addressInfo) {
    appendAddressInfoToFile(addressInfo);
    long blockNum = 0;
    try {
      Block block = wallet.getBlock(-1);
      if (block != null) {
        blockNum = block.getBlockHeader().toBuilder().getRawData().getNumber();
      }
    } catch (Exception e) {
      e.printStackTrace();
    }

    ivkMapScanBlockNum.put( ByteArray.toHexString(addressInfo.getIvk()), blockNum );
    updateIvkAndBlockNum(ByteArray.toHexString(addressInfo.getIvk()), blockNum);

    return true;
  }

  /**
   * append ivk and block num relationship to file tail
   * @param ivk
   * @param blockNum
   * @return
   */
  private boolean updateIvkAndBlockNum(final String ivk, long blockNum ) {
    synchronized (IVK_AND_NUM_FILE_NAME) {
      String date = ivk + ";" + blockNum;
      ZenUtils.appendToFileTail(IVK_AND_NUM_FILE_NAME, date);
    }
    return true;
  }

  /**
   * update ivk and block num
   * @return
   */
  private boolean updateIvkAndBlockNumFile() {
    synchronized (IVK_AND_NUM_FILE_NAME) {
      ZenUtils.clearFile(IVK_AND_NUM_FILE_NAME);
      for (Entry<String, Long> entry : ivkMapScanBlockNum.entrySet()) {
        String date = entry.getKey() + ";" + entry.getValue();
        ZenUtils.appendToFileTail(IVK_AND_NUM_FILE_NAME, date);
      }
    }
    return true;
  }

  /**
   * load ivk and block num relationship from file
   * @return
   */
  private boolean loadIvkFromFile() {
    ivkMapScanBlockNum.clear();
    List<String> list = ZenUtils.getListFromFile(IVK_AND_NUM_FILE_NAME);
    for (int i=0; i<list.size(); ++i) {
      String[] sourceStrArray = list.get(i).split(";");
      if (sourceStrArray.length != 2) {
        System.err.println("len is not right.");
        return false;
      }
      ivkMapScanBlockNum.put(sourceStrArray[0], Long.valueOf(sourceStrArray[1]));
    }
    return true;
  }

  /**
   * get shielded address list
   * @return
   */
  public List<String> getShieldedAddressList() {
    List<String>  addressList = new ArrayList<>();
    for (Entry<String, ShieldedAddressInfo> entry : shieldedAddressInfoMap.entrySet()) {
      addressList.add(entry.getKey());
    }
    return addressList;
  }

  /**
   * sort by value of UTXO
   * @return
   */
  public List<String> getvalidateSortUtxoList() {
    List<Map.Entry<Long, ShieldedNoteInfo>> list = new ArrayList<>(utxoMapNote.entrySet());
    Collections.sort(list, (Entry<Long, ShieldedNoteInfo> o1, Entry<Long, ShieldedNoteInfo> o2) -> {
        if (o1.getValue().getValue() < o2.getValue().getValue()) {
          return 1;
        } else {
          return -1;
        }
      });

    List<String> utxoList = new ArrayList<>();
    for (Map.Entry<Long, ShieldedNoteInfo> entry : list ) {
      String string = entry.getKey() + " " + entry.getValue().getPaymentAddress() + " ";
      string += entry.getValue().getValue();
      string += " ";
      string += entry.getValue().getTrxId();
      string += " ";
      string += entry.getValue().getIndex();
      string += " ";
      string += "UnSpend";
      string += " ";
      string += ZenUtils.getMemo(entry.getValue().getMemo());
      utxoList.add(string);
    }
    return utxoList;
  }

  /**
   * update unspend note
   * @return
   */
  private boolean saveUnspendNoteToFile() {
    ZenUtils.clearFile(UNSPEND_NOTE_FILE_NAME);
    for (Entry<Long, ShieldedNoteInfo> entry : utxoMapNote.entrySet()) {
      String date = entry.getValue().encode();
      ZenUtils.appendToFileTail(UNSPEND_NOTE_FILE_NAME, date);
    }
    return true;
  }

  /**
   * load unspend note from file
   * @return
   */
  private boolean loadUnSpendNoteFromFile() {
    utxoMapNote.clear();

    List<String> list = ZenUtils.getListFromFile(UNSPEND_NOTE_FILE_NAME);
    for (int i = 0; i < list.size(); ++i) {
      ShieldedNoteInfo noteInfo = new ShieldedNoteInfo();
      noteInfo.decode(list.get(i));
      utxoMapNote.put(noteInfo.getNoteIndex(), noteInfo);

      if (noteInfo.getNoteIndex() > nodeIndex.get()) {
        nodeIndex.set(noteInfo.getNoteIndex());
      }
    }
    return true;
  }


  /**
   * append spend note to file tail
   * @return
   */
  private boolean saveSpendNoteToFile(ShieldedNoteInfo noteInfo) {
    String date = noteInfo.encode();
    ZenUtils.appendToFileTail(SPEND_NOTE_FILE_NAME, date);
    return true;
  }

  /**
   * load spend note from file
   * @return
   */
  private boolean loadSpendNoteFromFile() {
    spendUtxoList.clear();
    List<String> list = ZenUtils.getListFromFile(SPEND_NOTE_FILE_NAME);
    for (int i = 0; i < list.size(); ++i) {
      ShieldedNoteInfo noteInfo = new ShieldedNoteInfo();
      noteInfo.decode(list.get(i));
      spendUtxoList.add(noteInfo);
    }
    return true;
  }


  /**
   * load shielded address from file
   * @return
   */
  public boolean loadAddressFromFile() {
    List<String> addressList = ZenUtils.getListFromFile(SHIELDED_ADDRESS_FILE_NAME);

    shieldedAddressInfoMap.clear();
    for (String addressString : addressList ) {
      ShieldedAddressInfo addressInfo = new ShieldedAddressInfo();
      if ( addressInfo.decode(addressString) ) {
        shieldedAddressInfoMap.put(addressInfo.getAddress(), addressInfo);
      } else {
        System.out.println("*******************");
      }
    }
    return true;
  }

  /**
   * put new shielded address to address list
   * @param addressInfo
   * @return
   */
  public boolean appendAddressInfoToFile(final ShieldedAddressInfo addressInfo ) {
    String shieldedAddress = addressInfo.getAddress();
    if ( !StringUtil.isNullOrEmpty( shieldedAddress ) ) {
      String addressString = addressInfo.encode();
      ZenUtils.appendToFileTail(SHIELDED_ADDRESS_FILE_NAME, addressString);

      shieldedAddressInfoMap.put(shieldedAddress, addressInfo);
    }
    return true;
  }

}