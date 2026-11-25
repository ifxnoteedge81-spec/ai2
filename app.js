// Reads downloads/version.json, which the GitHub Actions workflow generates for every successful build.
// If it is missing, the page says so and the download button stays disabled: there is no fake link.
(function () {
  var $ = function (id) { return document.getElementById(id); };
  function fmtSize(b) { return b >= 1048576 ? (b / 1048576).toFixed(1) + " MB" : Math.round(b / 1024) + " KB"; }

  fetch("downloads/version.json", { cache: "no-store" })
    .then(function (r) { if (!r.ok) throw new Error("HTTP " + r.status); return r.json(); })
    .then(function (v) {
      $("version").textContent = v.version + " (build " + v.versionCode + ")";
      $("date").textContent = new Date(v.date).toLocaleString();
      $("size").textContent = fmtSize(v.sizeBytes);
      $("build").textContent = v.signed ? "Release (production-signed)" : "Debug-signed (not a production release)";
      $("minAndroid").textContent = v.minAndroid;
      $("sha").textContent = v.sha256;
      var dl = $("dl");
      dl.href = "downloads/" + v.apk;
      dl.setAttribute("download", "PersonalAIComputer.apk");
      dl.classList.remove("disabled");
      dl.removeAttribute("aria-disabled");
      dl.textContent = "Download APK (" + fmtSize(v.sizeBytes) + ")";
      $("meta").hidden = false;
      $("status").textContent = "Latest successful build: " + v.version;
      var ul = $("changelog");
      if (v.changelog && v.changelog.length) {
        ul.innerHTML = "";
        v.changelog.forEach(function (c) { var li = document.createElement("li"); li.textContent = c; ul.appendChild(li); });
      }
    })
    .catch(function () {
      var s = $("status");
      s.textContent = "No build has been published to this site yet (downloads/version.json not found).";
      s.classList.add("error");
    });
})();
