// Annoyances other than cookie notices, hand-written imitations. Same shape as cookie.mjs.
export const newsletter = {
  en: [
    { t: 'Get 10% off your first order. Join our newsletter for early access to sales and new arrivals.', b: ['Sign me up', 'No thanks'], d: 1, email: true },
    { t: 'Don’t miss a story. Our morning briefing lands in your inbox every weekday.', b: ['Subscribe', 'Maybe later', 'Close'], d: 1, email: true },
    { t: 'Stay in the loop! Be the first to hear about new recipes and giveaways.', b: ['Join now', '×'], d: 1, email: true },
    { t: 'Wait! Before you go, spin the wheel for a chance to win a discount code.', b: ['Try my luck', 'I don’t want a discount'], d: 1, email: true },
  ],
  de: [{ t: 'Melde dich für unseren Newsletter an und erhalte 15 % Rabatt auf deine erste Bestellung.', b: ['Jetzt anmelden', 'Nein danke'], d: 1, email: true }],
  fr: [{ t: 'Inscrivez-vous à notre newsletter et recevez nos meilleurs articles chaque semaine.', b: ['Je m’inscris', 'Non merci'], d: 1, email: true }],
  es: [{ t: 'Suscríbete a nuestro boletín y consigue un 10 % de descuento en tu primera compra.', b: ['Suscribirme', 'Ahora no'], d: 1, email: true }],
  it: [{ t: 'Iscriviti alla newsletter per ricevere offerte esclusive e novità.', b: ['Iscriviti', 'No, grazie'], d: 1, email: true }],
  nl: [{ t: 'Schrijf je in voor onze nieuwsbrief en ontvang als eerste onze aanbiedingen.', b: ['Inschrijven', 'Nee, bedankt'], d: 1, email: true }],
  pl: [{ t: 'Zapisz się do newslettera i odbierz 10% rabatu na pierwsze zakupy.', b: ['Zapisz się', 'Nie, dziękuję'], d: 1, email: true }],
  cs: [{ t: 'Přihlaste se k odběru novinek a získejte slevu 10 % na první nákup.', b: ['Přihlásit', 'Ne, děkuji'], d: 1, email: true }],
  et: [
    { t: 'Liitu meie uudiskirjaga ja saa esimeselt ostult 10% allahindlust. Saadame parimad pakkumised otse sinu postkasti.', b: ['Liitun', 'Ei, aitäh'], d: 1, email: true },
    { t: 'Ära jää ilma! Telli nädala kokkuvõte e-postile.', b: ['Telli', 'Sulge'], d: 1, email: true },
  ],
  fi: [{ t: 'Tilaa uutiskirjeemme ja saat 10 % alennuksen ensimmäisestä tilauksestasi.', b: ['Tilaa', 'Ei kiitos'], d: 1, email: true }],
  sv: [{ t: 'Prenumerera på vårt nyhetsbrev och få 10 % rabatt på ditt första köp.', b: ['Prenumerera', 'Nej tack'], d: 1, email: true }],
  lv: [{ t: 'Piesakies mūsu jaunumu vēstulei un saņem 10% atlaidi pirmajam pirkumam.', b: ['Pieteikties', 'Nē, paldies'], d: 1, email: true }],
  lt: [{ t: 'Užsiprenumeruokite naujienlaiškį ir gaukite 10 % nuolaidą pirmam pirkiniui.', b: ['Prenumeruoti', 'Ne, ačiū'], d: 1, email: true }],
};

export const notification_prompt = {
  en: [
    { t: 'Get breaking news alerts. Allow notifications to hear about the biggest stories first.', b: ['Allow', 'Not now'], d: 1 },
    { t: 'Turn on notifications? We’ll only send you the important stuff.', b: ['No thanks', 'Turn on'], d: 0 },
  ],
  de: [{ t: 'Verpassen Sie keine Eilmeldung mehr. Aktivieren Sie unsere Push-Benachrichtigungen.', b: ['Später', 'Aktivieren'], d: 0 }],
  fr: [{ t: 'Recevez nos alertes info en temps réel. Activer les notifications ?', b: ['Plus tard', 'Activer'], d: 0 }],
  es: [{ t: '¿Quieres recibir notificaciones con las últimas noticias?', b: ['No, gracias', 'Permitir'], d: 0 }],
  it: [{ t: 'Attiva le notifiche per ricevere le notizie più importanti in tempo reale.', b: ['Non ora', 'Attiva'], d: 0 }],
  pl: [{ t: 'Chcesz otrzymywać powiadomienia o najważniejszych wiadomościach?', b: ['Nie teraz', 'Zezwól'], d: 0 }],
  et: [
    { t: 'Kas soovid saada teavitusi olulisematest uudistest? Lülita teavitused sisse.', b: ['Mitte praegu', 'Luba'], d: 0 },
    { t: 'Ole kursis! Saadame sulle teate, kui ilmub uus artikkel.', b: ['Luba teavitused', 'Hiljem'], d: 1 },
  ],
  fi: [{ t: 'Haluatko ilmoituksen tärkeimmistä uutisista? Salli ilmoitukset.', b: ['Ei nyt', 'Salli'], d: 0 }],
  sv: [{ t: 'Vill du få notiser om de viktigaste nyheterna?', b: ['Inte nu', 'Tillåt'], d: 0 }],
  lv: [{ t: 'Vai vēlaties saņemt paziņojumus par svarīgākajām ziņām?', b: ['Ne tagad', 'Atļaut'], d: 0 }],
  lt: [{ t: 'Ar norite gauti pranešimus apie svarbiausias naujienas?', b: ['Ne dabar', 'Leisti'], d: 0 }],
};

export const paywall_teaser = {
  en: [
    { t: 'You’ve reached your limit of free articles this month. Subscribe for unlimited access from €1 a week.', b: ['Subscribe now', 'Sign in'], d: -1 },
    { t: 'This article is for subscribers only. Support independent journalism and get full access.', b: ['See offers', 'Already a subscriber? Log in'], d: -1 },
  ],
  de: [{ t: 'Sie haben Ihr Kontingent an kostenlosen Artikeln erreicht. Jetzt weiterlesen mit unserem Digital-Abo.', b: ['Jetzt abonnieren', 'Anmelden'], d: -1 }],
  fr: [{ t: 'Cet article est réservé aux abonnés. Abonnez-vous pour lire la suite.', b: ['Je m’abonne', 'Se connecter'], d: -1 }],
  es: [{ t: 'Has alcanzado el límite de artículos gratuitos. Suscríbete para seguir leyendo.', b: ['Suscribirme', 'Iniciar sesión'], d: -1 }],
  it: [{ t: 'Hai raggiunto il numero massimo di articoli gratuiti. Abbonati per continuare a leggere.', b: ['Abbonati', 'Accedi'], d: -1 }],
  pl: [{ t: 'Ten artykuł jest dostępny tylko dla prenumeratorów. Wykup dostęp, aby czytać dalej.', b: ['Kup dostęp', 'Zaloguj się'], d: -1 }],
  et: [
    { t: 'See artikkel on mõeldud tellijatele. Telli digipakett ja loe edasi piiranguteta.', b: ['Telli', 'Logi sisse'], d: -1 },
    { t: 'Oled sel kuul lugenud kõik tasuta artiklid. Jätka lugemist tellimusega.', b: ['Vaata pakkumisi', 'Olen juba tellija'], d: -1 },
  ],
  fi: [{ t: 'Tämä artikkeli on vain tilaajille. Tilaa ja lue rajattomasti.', b: ['Tilaa', 'Kirjaudu'], d: -1 }],
  sv: [{ t: 'Den här artikeln är bara för prenumeranter. Bli prenumerant för att läsa vidare.', b: ['Prenumerera', 'Logga in'], d: -1 }],
  lv: [{ t: 'Šis raksts pieejams tikai abonentiem. Abonē, lai lasītu tālāk.', b: ['Abonēt', 'Pieslēgties'], d: -1 }],
  lt: [{ t: 'Šis straipsnis skirtas tik prenumeratoriams. Užsiprenumeruokite ir skaitykite toliau.', b: ['Prenumeruoti', 'Prisijungti'], d: -1 }],
};

export const app_install = {
  en: [
    { t: 'Open in app. Get the full experience in our free app.', b: ['Open', '×'], d: 1 },
    { t: 'Our app is faster. Download it from the App Store or Google Play.', b: ['Get the app', 'Continue in browser'], d: 1 },
    { t: 'News App · Free · Install', b: ['Install', 'Close'], d: 1 },
  ],
  de: [{ t: 'In der App weiterlesen – schneller und mit Push-Nachrichten.', b: ['App öffnen', 'Im Browser bleiben'], d: 1 }],
  fr: [{ t: 'Profitez d’une meilleure expérience avec notre application.', b: ['Ouvrir l’application', 'Continuer sur le site'], d: 1 }],
  es: [{ t: 'Descarga nuestra app para una experiencia más rápida.', b: ['Abrir en la app', 'Ahora no'], d: 1 }],
  it: [{ t: 'Leggi nell’app: è gratis e più veloce.', b: ['Apri nell’app', 'Continua sul browser'], d: 1 }],
  pl: [{ t: 'Pobierz naszą aplikację i czytaj wygodniej.', b: ['Otwórz w aplikacji', 'Zamknij'], d: 1 }],
  et: [
    { t: 'Loe uudiseid meie äpis – kiirem ja mugavam. Laadi alla App Store’ist või Google Play’st.', b: ['Ava äpis', 'Jätka brauseris'], d: 1 },
    { t: 'Proovi meie uut rakendust!', b: ['Laadi alla', 'Sulge'], d: 1 },
  ],
  fi: [{ t: 'Lue uutiset sovelluksessamme. Lataa ilmaiseksi.', b: ['Avaa sovelluksessa', 'Jatka selaimessa'], d: 1 }],
  sv: [{ t: 'Läs mer i vår app – snabbare och smidigare.', b: ['Öppna i appen', 'Fortsätt i webbläsaren'], d: 1 }],
  lv: [{ t: 'Lasi ziņas mūsu lietotnē – ātrāk un ērtāk.', b: ['Atvērt lietotnē', 'Turpināt pārlūkā'], d: 1 }],
  lt: [{ t: 'Skaitykite naujienas mūsų programėlėje.', b: ['Atidaryti programėlėje', 'Tęsti naršyklėje'], d: 1 }],
};

export const login_wall = {
  en: [
    { t: 'Sign in to continue reading. Create a free account to keep browsing.', b: ['Continue with Google', 'Sign up with email', 'Log in'], d: -1, password: false },
    { t: 'Log in to see more. Join to save ideas and follow people you love.', b: ['Log in', 'Sign up'], d: -1, password: true },
  ],
  de: [{ t: 'Melde dich an, um weiterzulesen. Die Registrierung ist kostenlos.', b: ['Anmelden', 'Kostenlos registrieren'], d: -1, password: true }],
  fr: [{ t: 'Connectez-vous pour continuer. Créez un compte gratuit en quelques secondes.', b: ['Se connecter', 'Créer un compte'], d: -1 }],
  et: [{ t: 'Logi sisse, et edasi lugeda. Konto loomine on tasuta.', b: ['Logi sisse', 'Loo konto'], d: -1, password: true }],
  fi: [{ t: 'Kirjaudu sisään jatkaaksesi lukemista.', b: ['Kirjaudu', 'Luo tili'], d: -1 }],
  pl: [{ t: 'Zaloguj się, aby czytać dalej. Rejestracja jest darmowa.', b: ['Zaloguj się', 'Zarejestruj się'], d: -1 }],
};

export const chat_widget = {
  en: [
    { t: 'Hi there! Need help? Chat with us.', b: ['Chat'], d: -1 },
    { t: 'Questions? Our team is online.', b: ['Start chat', 'Close'], d: 1 },
  ],
  de: [{ t: 'Haben Sie Fragen? Wir helfen gern im Live-Chat.', b: ['Chat starten'], d: -1 }],
  et: [{ t: 'Tere! Kuidas saame aidata?', b: ['Alusta vestlust'], d: -1 }],
  fi: [{ t: 'Hei! Voinko auttaa?', b: ['Avaa chat'], d: -1 }],
};

export const survey = {
  en: [
    { t: 'Would you recommend this shop to a friend or colleague? Pick a score from 0 to 10.', b: ['Submit', 'No thanks'], d: 1 },
    { t: 'Quick question: did you find what you were looking for today?', b: ['Yes', 'No', 'Close'], d: 2 },
    { t: 'Rate your experience ★★★★★', b: ['Send feedback', 'Skip'], d: 1 },
  ],
  de: [{ t: 'Wie zufrieden sind Sie mit unserer Website? Ihre Meinung hilft uns.', b: ['Zur Umfrage', 'Nein danke'], d: 1 }],
  fr: [{ t: 'Donnez-nous votre avis : 2 minutes pour améliorer notre site.', b: ['Répondre', 'Non merci'], d: 1 }],
  et: [{ t: 'Kuidas hindad meie veebilehte? Vasta lühikesele küsitlusele.', b: ['Vasta', 'Ei, aitäh'], d: 1 }],
  fi: [{ t: 'Kerro mielipiteesi! Vastaa lyhyeen kyselyyn.', b: ['Vastaa', 'Ei kiitos'], d: 1 }],
  sv: [{ t: 'Hur nöjd är du med vår webbplats? Svara på en kort enkät.', b: ['Svara', 'Nej tack'], d: 1 }],
};
