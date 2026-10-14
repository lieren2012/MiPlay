import com.android.apksig.ApkVerifier;
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.base.reference.BaseStringReference;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import java.io.File;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.HexFormat;

/** Checks the actual signed APK's runtime trust pin, not the decoded build input. */
public final class VerifyRuntimeSigningPin {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected APK path");
        File apk = new File(args[0]);
        ApkVerifier.Result signature = new ApkVerifier.Builder(apk).build().verify();
        if (!signature.isVerified()) {
            throw new IllegalStateException("APK signature invalid: " + signature.getErrors());
        }
        String pin = null;
        for (Object item : DexFileFactory.loadDexContainer(apk, null).getEntry("classes.dex").getDexFile().classSection) {
            ClassDef type = (ClassDef) item;
            if (!type.getType().equals("Ldev/carwith/bridge/BridgeApp;")) continue;
            for (Object methodItem : type.getVirtualMethods()) {
                Method method = (Method) methodItem;
                if (!method.getName().equals("onCreate")) continue;
                if (method.getImplementation() == null) continue;
                for (Object instructionItem : method.getImplementation().getInstructions()) {
                    Instruction instruction = (Instruction) instructionItem;
                    if (!(instruction instanceof ReferenceInstruction)) continue;
                    Object reference = ((ReferenceInstruction) instruction).getReference();
                    if (!(reference instanceof BaseStringReference)) continue;
                    String value = ((BaseStringReference) reference).getString();
                    if (!value.matches("[0-9a-f]{128}")) continue;
                    StringBuilder decoded = new StringBuilder(64);
                    for (int i = 0; i < value.length(); i += 2) {
                        decoded.append((char) (Integer.parseInt(value.substring(i, i + 2), 16) ^ 0x5a));
                    }
                    if (!decoded.toString().matches("[0-9a-f]{64}")) continue;
                    if (pin != null) throw new IllegalStateException("Multiple runtime signing pins");
                    pin = decoded.toString();
                }
            }
        }
        if (pin == null) throw new IllegalStateException("Runtime signing pin missing");
        if (signature.getSignerCertificates().isEmpty()) {
            throw new IllegalStateException("APK signer certificate missing");
        }
        for (X509Certificate certificate : signature.getSignerCertificates()) {
            String actual = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
            if (!pin.equals(actual)) {
                throw new IllegalStateException("Runtime certificate pin mismatch: trusted="
                    + pin + "; APK signer=" + actual);
            }
        }
        System.out.println("Runtime signing pin matches verified APK signer: " + pin);
    }
}
