package com.siliconlabs.bledemo.features.firmware_browser.domain

object UiStrings {
    // Firmware Browser
    var firmwareBrowserTitle = "Sélection du firmware"
    var selectProduct = "Sélectionner le produit"
    var selectPartNumber = "Sélectionner le numéro de pièce"
    var selectCardToUpdate = "Sélectionner la carte à mettre à jour"
    var antenna = "Antenne"
    var power = "Puissance"
    var both = "Antenne + Puissance"
    var connecting = "Connexion au serveur de firmware..."
    var downloading = "Téléchargement du firmware..."
    var firmwareReady = "Firmware prêt"
    var noProductsFound = "Aucun produit trouvé sur le serveur de firmware."
    var connectionFailed = "Échec de connexion au serveur de firmware"
    var failedToListPns = "Échec de la liste des numéros de pièce"
    var failedToReadConfig = "Échec de lecture de la configuration"
    var failedToDownload = "Échec du téléchargement du firmware"
    var noCredentials = "Identifiants SFTP non configurés.\n\nVeuillez placer le fichier Secret_OTAU.ini dans le stockage de la tablette et relancer l'application."
    var retry = "Réessayer"
    var back = "Retour"
    var changeProduct = "Changer de produit"

    // Model validation
    var modelMismatchTitle = "Modèle incompatible"
    var modelMismatchMessage = "Le modèle de l'appareil connecté \"%s\" ne correspond pas au produit sélectionné (attendu : %s).\n\nVeuillez déconnecter et vérifier que vous avez le bon appareil, ou continuer en mode opérateur."
    var modelMismatchOverride = "Mode opérateur"
    var disconnect = "Déconnecter"

    // Operator override
    var overridePromptTitle = "Informations de l'appareil illisibles"
    var overridePromptMessage = "Impossible de lire les informations de l'appareil. Continuer avec le mode opérateur ?"
    var overrideYes = "Oui"
    var overrideCodeTitle = "Saisir le code opérateur"
    var overrideCodeHint = "Code"
    var overrideConfirm = "Confirmer"
    var overrideCancel = "Annuler"
    var overrideIncorrectCode = "Code incorrect. Veuillez réessayer."

    // OTA launch errors
    var otaNoFileSelected = "Aucun fichier OTA sélectionné. Veuillez redémarrer l'application et sélectionner un fichier OTA."
    var otaDeviceNotReady = "L'appareil n'est pas prêt pour l'OTA. Veuillez patienter ou déconnecter puis reconnecter."

    // Post-OTA: new firmware exposes a characteristic the old one didn't, but
    // Android's stale GATT cache hides it and can't be cleared programmatically on
    // this platform. Not a failure — inform the operator to clear the bond + reconnect.
    var postOtaCacheHint = "Mise à jour envoyée. La nouvelle version n'a pas pu être lue automatiquement.\n\nVeuillez supprimer l'association (menu « Delete Bond ») puis reconnecter dans l'application pour vérifier la version."

    // Device status
    var statusPreOta = "Connecté — Pré-OTA"
    var statusPreOtaBoth = "Connecté — Pré-OTA (Antenne + Puissance)"
    var statusUploading = "Mise à jour en cours..."
    var statusUploadingAntenna = "Mise à jour Antenne (1/2) en cours..."
    var statusUploadingPower = "Mise à jour Puissance (2/2) en cours..."
    var statusRebooting = "Redémarrage de l'appareil..."
    var statusReconnecting = "Reconnexion en cours..."
    var statusPostOta = "Connecté — Post-OTA"
    var statusSecondOta = "Antenne (1/2) terminée.\nLancement de la mise à jour Puissance (2/2)..."
    var statusPowerTransfer = "Transfert série vers carte Puissance...\nVeuillez patienter (~1m30s)"
    var statusAlreadyUpToDate = "Firmware déjà à jour"
    var versionsAlreadyMatch = "Déjà à jour"
    var statusReconnectFailed = "Échec de reconnexion.\nVeuillez déconnecter et réessayer."
    var statusOtaRetrying = "Connexion perdue pendant la mise à jour.\nReconnexion et reprise... (tentative %d/%d)"
    var statusOtaRetryFailed = "Échec après %d tentatives.\nVeuillez déconnecter et réessayer."

    // Toast messages
    var firmwareSelected = "Firmware sélectionné et prêt pour l'OTA"
    var noFirmwareSelected = "Aucun firmware sélectionné"

    // Batch number (OTA history)
    var batchNumberHint = "Numéro de production"
    var batchEntryTitle = "Saisir le numéro de production pour %s"
    var batchEntryConfirm = "Continuer"
    var batchEntryRequired = "Le numéro de production est obligatoire."
    var batchEntryInvalidFormat = "Format de numéro de production invalide."
    var missingAppSettings = "Fichier app_settings.ini manquant, illisible ou invalide pour ce produit. Impossible de continuer"

    // OTA history screen
    var otaHistoryButtonLabel = "Historique OTA"
    var otaHistoryTitle = "Historique des mises à jour OTA"
    var otaHistoryTabletId = "ID tablette : %s"
    var otaHistoryFilterHint = "Filtrer par numéro de production"
    var otaHistoryExportCsv = "Exporter en CSV"
    var otaHistoryEmpty = "Aucune mise à jour enregistrée."
    var otaHistoryNoMatch = "Aucun résultat pour ce numéro de production."
    var otaHistoryItemBatch = "N° de production : %s"
    var otaHistoryExportSuccess = "Export CSV enregistré : %s"
    var otaHistoryExportFailed = "Échec de l'export CSV"
    var otaHistoryResultPass = "Réussite"
    var otaHistoryResultFail = "Échec"
    var otaHistoryResultAbandoned = "Non terminée"

    // ABANDONED history record: OTA launched, screen closed with no verdict
    var otaAbandonedNoUpload = "OTA non terminée : aucun envoi (ancien firmware probable)"
    var otaAbandonedUpload = "OTA non terminée : envoi interrompu à %.1f %%"
    var otaAbandonedVerify = "OTA non terminée : envoi fini, vérification non faite"
    var otaAbandonedDisconnect = "Déconnexion inattendue (status=%d)"
    var otaAbandonedRetries = "Échec après %d tentatives"
    var otaHistoryPendingWarning = "%d enregistrement(s) non envoyé(s) à la base de données (le plus ancien : %s).\nVérifier la connexion Wi-Fi de la tablette."
    var otaHistoryUnreadableWarning = "%d enregistrement(s) illisible(s), jamais envoyé(s). Prévenir le responsable."
    var otaHistoryRetrySync = "Réessayer l'envoi"
    var otaHistoryRetryStarted = "Envoi relancé"
    var otaHistoryButtonPending = "Historique OTA (%d non envoyé(s))"
}
