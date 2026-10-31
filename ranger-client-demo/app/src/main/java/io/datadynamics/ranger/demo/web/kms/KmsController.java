package io.datadynamics.ranger.demo.web.kms;

import io.datadynamics.ranger.client.kms.RangerKmsClient;
import io.datadynamics.ranger.client.model.EncryptedKeyVersion;
import io.datadynamics.ranger.client.model.KeyMetadata;
import io.datadynamics.ranger.demo.trace.SdkCall;
import io.datadynamics.ranger.demo.trace.SdkCalls;
import io.datadynamics.ranger.demo.web.pdp.Kv;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Ranger KMS: keys, versions, rollover, delete, cache invalidation and the encrypted-key (EEK) flow. */
@Controller
@RequestMapping("/kms")
public class KmsController {
    private final RangerKmsClient kms;
    private final SdkCalls calls;

    public KmsController(RangerKmsClient kms, SdkCalls calls) {
        this.kms = kms;
        this.calls = calls;
    }

    @GetMapping
    public String page(Model model) {
        keys(model);
        return "kms/kms";
    }

    /** The key table alone (refreshed after every change). */
    @GetMapping("/keys")
    public String keyTable(Model model) {
        keys(model);
        return "kms/kms :: keys";
    }

    @PostMapping("/create")
    public String create(@RequestParam String name, @RequestParam(defaultValue = "AES/CTR/NoPadding") String cipher,
                         @RequestParam(defaultValue = "128") String length, @RequestParam(defaultValue = "") String description,
                         @RequestParam(defaultValue = "") String attributes, Model model) {
        String desc = description.isBlank() ? null : description;
        String code = "kms.createKey(" + Kv.str(name) + ", " + Kv.str(cipher) + ", " + length + ", " + Kv.str(desc) + ", "
                + (attributes.isBlank() ? "null" : mapLiteral(attributes)) + ")";
        return change(model, calls.run(code, () -> {
            Map<String, String> attrs = attributes.isBlank() ? null : Kv.map(attributes);
            return kms.createKey(name, cipher, Integer.parseInt(length.trim()), desc, attrs);
        }));
    }

    @PostMapping("/{name}/rollover")
    public String rollover(@PathVariable String name, Model model) {
        return change(model, calls.run("kms.rolloverKey(" + Kv.str(name) + ")", () -> kms.rolloverKey(name)));
    }

    @PostMapping("/{name}/delete")
    public String delete(@PathVariable String name, Model model) {
        return change(model, calls.runVoid("kms.deleteKey(" + Kv.str(name) + ")", () -> kms.deleteKey(name)));
    }

    @PostMapping("/{name}/invalidate")
    public String invalidate(@PathVariable String name, Model model) {
        model.addAttribute("call", calls.runVoid("kms.invalidateCache(" + Kv.str(name) + ")", () -> kms.invalidateCache(name)));
        return "fragments/call :: call";
    }

    @PostMapping("/{name}/versions")
    public String versions(@PathVariable String name, Model model) {
        model.addAttribute("call", calls.run("kms.versions(" + Kv.str(name) + ")", () -> kms.versions(name)));
        return "fragments/call :: call";
    }

    @PostMapping("/{name}/current")
    public String current(@PathVariable String name, Model model) {
        model.addAttribute("call", calls.run("kms.currentVersion(" + Kv.str(name) + ")", () -> kms.currentVersion(name)));
        return "fragments/call :: call";
    }

    @PostMapping("/{name}/metadata")
    public String metadata(@PathVariable String name, Model model) {
        model.addAttribute("call", calls.run("kms.metadata(" + Kv.str(name) + ")", () -> kms.metadata(name)));
        return "fragments/call :: call";
    }

    /**
     * Generate an encrypted data key, decrypt it, then re-encrypt it with the key's current version.
     * Optionally rolls the key over first, so re-encryption visibly moves the EEK to the new version.
     */
    @SuppressWarnings("unchecked")
    @PostMapping("/eek")
    public String eek(@RequestParam String name, @RequestParam(defaultValue = "false") boolean rollover, Model model) {
        List<SdkCall> steps = new ArrayList<>();
        SdkCall gen = calls.run("List<EncryptedKeyVersion> eeks = kms.generateEek(" + Kv.str(name) + ", 1)",
                () -> kms.generateEek(name, 1));
        steps.add(gen);
        if (gen.ok() && !((List<?>) gen.value()).isEmpty()) {
            EncryptedKeyVersion eek = ((List<EncryptedKeyVersion>) gen.value()).get(0);
            steps.add(calls.run("KeyVersion dek = kms.decryptEek(" + Kv.str(name) + ", eeks.get(0))", () -> kms.decryptEek(name, eek)));
            if (rollover) {
                steps.add(calls.run("kms.rolloverKey(" + Kv.str(name) + ")", () -> kms.rolloverKey(name)));
            }
            steps.add(calls.run("EncryptedKeyVersion moved = kms.reencryptEek(" + Kv.str(name) + ", eeks.get(0))",
                    () -> kms.reencryptEek(name, eek)));
        }
        model.addAttribute("steps", steps);
        return "kms/kms :: eekResult";
    }

    private String change(Model model, SdkCall call) {
        model.addAttribute("call", call);
        keys(model);
        return "kms/kms :: change";
    }

    private void keys(Model model) {
        SdkCall names = calls.run("kms.names()", kms::names);
        model.addAttribute("namesCall", names);
        if (names.ok()) {
            List<String> list = names.value();
            model.addAttribute("names", list);
            if (!list.isEmpty()) {
                SdkCall meta = calls.run("kms.metadata(names)", () -> kms.metadata(list));
                model.addAttribute("metaCall", meta);
                if (meta.ok()) {
                    Map<String, String> created = new HashMap<>();
                    for (KeyMetadata m : meta.<List<KeyMetadata>>value()) {
                        if (m != null && m.getCreated() != null) {
                            created.put(m.getName(), Instant.ofEpochMilli(m.getCreated()).toString());
                        }
                    }
                    model.addAttribute("created", created);
                }
            }
        }
    }

    private static String mapLiteral(String attributes) {
        try {
            return Kv.mapOf(Kv.map(attributes));
        } catch (IllegalArgumentException e) {
            return Kv.str(attributes);
        }
    }
}
