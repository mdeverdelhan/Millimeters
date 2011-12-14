/*
* Copyright (C) 2010 Marc de Verdelhan (http://www.verdelhan.eu/)
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package com.droideilhan.millimeters;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.preference.PreferenceManager;
import android.text.method.LinkMovementMethod;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.TabHost;
import android.widget.TextView;

public class Millimeters extends Activity implements SensorEventListener, OnClickListener {

    /** Identifiants des messages */
    private static final int MSG_FIN_THREAD_CALCUL = 0x001; // Fin du thread de calcul
    private static final int MSG_VALEUR_DISTANCE = 0x002; // Valeur textuelle de la distance
    private static final int MSG_ERR_TROP_LENT = 0x003; // Erreur : mouvement trop lent
    private static final int MSG_ERR_TROP_RAPIDE = 0x004; // Erreur : mouvement trop rapide
    private static final int MSG_REINIT = 0x005; // Réinitialisation pour une nouvelle mesure
    
    /** Identifiants des erreurs lors de la phase de calcul */
    private static final int ERR_TROP_LENT = 0x001;
    private static final int ERR_TROP_RAPIDE = 0x002;
    
    /** Identifiants des menus */
    public static final int MENU_PARAMETRES_ID = Menu.FIRST;
    public static final int MENU_QUITTER_ID = Menu.FIRST+1;
    
    private final static double VITESSE_MIN = -0.03; // Vitesse minimum acceptable

    private WakeLock wl; // WakeLock permettant de bloquer la mise en veille de l'écran.
    
    private SensorManager sensorMgr = null;
    private Sensor sensorAccel;

    private TextView txtValeurDistance; // TextView affichant la valeur de la distance mesurée

    private LinkedList<Double> listeAccelX = new LinkedList<Double>(); // Liste des accélérations sur l'axe des x
    private final LinkedList<Double> listeVitesses = new LinkedList<Double>(); // Liste des vitesses sur l'axe des x
    private LinkedList<Long> listeTimes = new LinkedList<Long>(); // Liste des timestamps (en nanosecondes)
    
    private boolean mesureEnCours = false; // true si la mesure est en cours, false sinon

    /** Bouton principal Start/Stop */
    private Button btnStartStop;
    private TextView labelStartStop;
    
    private ProgressDialog progressDialog; // Boite de dialogue de progression (affichée lors de l'éxecution du calcul)
    private Thread calculThread; // Thread de calcul de la mesure
    
    private Button buttonAutresApps; // Bouton des autres applications
    
    /** Préférences */
    private boolean prefSystemeImperial;
    
    /** Handler de réception des messages en provenance du thread de calcul */
    private Handler messageHandler = new Handler() {

        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            switch (msg.what) {
            case MSG_FIN_THREAD_CALCUL: // Fin du thread de calcul
                progressDialog.cancel();
                break;
            case MSG_VALEUR_DISTANCE: // Valeur textuelle de la distance
                txtValeurDistance.setText((String) msg.obj);
                //mesureLabel.loadData((String) msg.obj, "text/html", "UTF-8");
                break;
            case MSG_ERR_TROP_LENT: // Erreur : mouvement trop lent
                showErrorDialog(getString(R.string.texte_erreur_trop_lent));
                break;
            case MSG_ERR_TROP_RAPIDE: // Erreur : mouvement trop rapide
                showErrorDialog(getString(R.string.texte_erreur_trop_rapide));
                break;
            case MSG_REINIT: // Réinitialisation pour une nouvelle mesure
                // Inversion du bouton Start/Stop
                btnStartStop.setBackgroundResource(R.drawable.action_read);
                labelStartStop.setText(R.string.label_start);
                if (Millimeters.this.wl.isHeld()) {
                    Millimeters.this.wl.release(); // Fin du blocage de la mise en veille
                }
                break;
            default:
                //Log.d("handleMessage", "Message non prévu");
                break;
            }
        }

    }; // Fin du handle de réception des messages
    
    /**
     * Appelée quand l'activité est créée la première fois
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        
        // Gestion de la mise en veille du terminal (récupération du WakeLock)
        wl = ((PowerManager) getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "Millimeters");
        
        TabHost tabs = (TabHost) findViewById(R.id.tabhost);
        tabs.setup();
        
        // Création du premier onglet
        TabHost.TabSpec tabSpec = tabs.newTabSpec(getString(R.string.tag_onglet1));
        tabSpec.setContent(R.id.tab1);
        tabSpec.setIndicator(getString(R.string.label_onglet1), this.getResources().getDrawable(R.drawable.mesure));
        tabs.addTab(tabSpec);

        // Création du deuxième onglet
        tabSpec = tabs.newTabSpec(getString(R.string.tag_onglet2));
        tabSpec.setContent(R.id.tab2);
        tabSpec.setIndicator(getString(R.string.label_onglet2), this.getResources().getDrawable(R.drawable.crystal_clear_aide));
        tabs.addTab(tabSpec);
        
        tabs.setCurrentTab(0); // Au départ, affichage du premier onglet

        // Création du bouton
        btnStartStop = (Button) findViewById(R.id.btnStartStop);
        btnStartStop.setOnClickListener(this);
        labelStartStop = (TextView) findViewById(R.id.labelStartStop);
        
        txtValeurDistance = (TextView) findViewById(R.id.valeurDistance); // Création du TextView de la distance
        
        // Construction de la boite de dialogue de progression
        progressDialog = new ProgressDialog(this);
        progressDialog.setCancelable(false);
        progressDialog.setCanceledOnTouchOutside(false);
        progressDialog.setIndeterminate(true);
        progressDialog.setTitle(R.string.titre_calcul);
        progressDialog.setMessage(getString(R.string.texte_calcul));
        progressDialog.setButton(DialogInterface.BUTTON_NEUTRAL, getString(R.string.annuler_calcul), new DialogInterface.OnClickListener() {
            
            @Override
            public void onClick(DialogInterface dialog, int which) {
                // Si clic sur le bouton : on tue le thread de calcul
                calculThread.interrupt();
            }
        });
        
        // On rend le lien vers la vidéo Youtube clicable
        TextView texteConseils = (TextView) findViewById(R.id.texteConseils);
        texteConseils.setMovementMethod(LinkMovementMethod.getInstance());

        // Création du bouton des autres applications
        buttonAutresApps = (Button) findViewById(R.id.buttonAutresApps);
        buttonAutresApps.setOnClickListener(this);
    }
    
    
    /**
     * Appelée lors d'un clic sur le bouton "Menu"
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(Menu.NONE, MENU_PARAMETRES_ID, Menu.NONE, getString(R.string.menu_parametres)).setIcon(R.drawable.crystal_clear_parametres);
        menu.add(Menu.NONE, MENU_QUITTER_ID, Menu.NONE, getString(R.string.menu_quitter)).setIcon(R.drawable.crystal_clear_quitter);
        return super.onCreateOptionsMenu(menu);
    }
    
    /**
     * Appelée lors d'un choix (clic) parmi les items du menu d'options
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        switch (menuItem.getItemId()) {
        case MENU_PARAMETRES_ID:            
            startActivity(new Intent(this, Preferences.class));
            return true;
        case MENU_QUITTER_ID:
            this.finish();
            return true;
        default:
            return super.onOptionsItemSelected(menuItem);
        }
    }

    /**
     * Appelée lors d'un changement de précision de l'accéléromètre
     */
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        //Log.d("MILLIMETERS onAccuracyChanged","onAccuracyChanged: " + sensor + ", accuracy: " + accuracy);
    }

    /**
     * Appelée lors d'un changement de valeur du capteur
     */
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (mesureEnCours && (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER)) { // On gère les événements de l'accéléromètre quand la mesure est en cours
            listeTimes.add(event.timestamp); // On stocke en nanosecondes
            listeAccelX.add((double) event.values[0]);
        }
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        sensorMgr.unregisterListener(this, sensorAccel);
        sensorMgr = null;
    }

    @Override
    protected void onStart() {
        super.onStart();
        getPrefs();
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        sensorMgr = (SensorManager) getSystemService(SENSOR_SERVICE);
        sensorAccel = sensorMgr.getSensorList(Sensor.TYPE_ACCELEROMETER).get(0);
        if (!sensorMgr.registerListener(this, sensorAccel, SensorManager.SENSOR_DELAY_FASTEST)) {
            // L'accéléromètre n'est pas supporté par ce terminal
            sensorMgr.unregisterListener(this, sensorAccel);
        }
    }

    @Override
    public void onClick(View v) {
        
        if (v == btnStartStop) { // On a cliqué sur le bouton Start/Stop
            mesureEnCours = !mesureEnCours; // On inverse l'état de la mesure
            
            if(mesureEnCours) { // On lance une nouvelle mesure
                listeAccelX.clear();
                listeTimes.clear();
                listeVitesses.clear();
                
                // Inversion du bouton Start/Stop
                btnStartStop.setBackgroundResource(R.drawable.action_stop);
                labelStartStop.setText(R.string.label_stop);
                wl.acquire(); // L'écran reste allumé à partir de cet appel.
                
            } else { // On arrête une mesure en cours
                
                // Construction du thread de calcul du résultat
                calculThread = new Thread() {
                    /**
                     * Run du thread de calcul
                     */
                    public void run() {
                        int idErreur = calculeResultat();
                        messageHandler.sendMessage(Message.obtain(messageHandler, MSG_FIN_THREAD_CALCUL)); // Envoi du message de fin de thread
                        switch(idErreur) {
                        case ERR_TROP_LENT:
                            messageHandler.sendMessage(Message.obtain(messageHandler, MSG_ERR_TROP_LENT));
                            break;
                        case ERR_TROP_RAPIDE:
                            messageHandler.sendMessage(Message.obtain(messageHandler, MSG_ERR_TROP_RAPIDE));
                            break;
                        default:
                            break;
                        }
                        messageHandler.sendMessage(Message.obtain(messageHandler, MSG_REINIT)); // Réinitialisation pour une nouvelle mesure
                    }
                    
                    @Override
                    public void interrupt() {
                        super.interrupt();
                        messageHandler.sendMessage(Message.obtain(messageHandler, MSG_FIN_THREAD_CALCUL)); // Envoi du message de fin de thread
                        messageHandler.sendMessage(Message.obtain(messageHandler, MSG_REINIT)); // Réinitialisation pour une nouvelle mesure
                        try {
                            this.finalize();
                        } catch (Throwable e) {
                            e.printStackTrace();
                        }
                    }

                };
                calculThread.start(); // Lancement du thread de calcul
                progressDialog.show(); // Affichage de la boite de dialogue de progression
            }
        } else if(buttonAutresApps.equals(v)) { // Clic sur le bouton des autres applications
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=pub:droideilhan")));
            } catch(ActivityNotFoundException e) {
                showErrorDialog(getString(R.string.texte_erreur_market));
            }
        }
    }
    
    private void getPrefs() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefSystemeImperial = prefs.getBoolean("systemePref", false);
    }

    /**
     * Affiche une boite de dialogue d'erreur du message identifié par messageId
     * @param message le message à afficher
     */
    private void showErrorDialog(String message) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage(message)
               .setCancelable(false)
               .setTitle(R.string.titre_erreur)
               .setNeutralButton("OK", new DialogInterface.OnClickListener() {
                   public void onClick(DialogInterface dialog, int id) {
                        dialog.cancel();
                   }
               });
        AlertDialog alert = builder.create();
        alert.show();
    }
    
    /**
     * Calcule et affiche le résultat de la mesure
     */
    private int calculeResultat() {
        
        /**
         * Algorithme de calcul de la longueur du déplacement :
         *         On a la liste des accélérations instantanées
         *         On met les accélérations de départ à zéro (correction du biais introduit par l'accéléromètre)
         *         On supprime les accélérations de fin (correction du biais introduit par l'accéléromètre)
         *         On recalibre les autres mesures d'accélération avec la moyenne des 2 biais (début ET fin)
         *         On calcule les vitesses instantanées en fonction des accélérations corrigées
         *         Si on trouve des vitesses négatives et <= -0.03 : on modifie le biais, on recalibre les accélérations, on recalcule les vitesses instantanées
         *         Sinon, on passe toutes les vitesses < à 0.01 à zéro
         *         Avec la liste des vitesses : on calcule la distance parcourue (longueur de déplacement)
         */
        
        // La liste est vide, on a parcouru 0 metre
        if(listeAccelX.isEmpty()) {
            effacerTextViewDeplacement();
            return ERR_TROP_LENT;
        }
        
        // La liste n'est pas vide, on cherche le choc de départ
        int indiceChocDepart = 0;
        int decalageAvant = 6; // On se projette de 6 en avant (dans la liste)
        double accelCourante;
        int finParcoursListe = listeAccelX.size()-decalageAvant;
        Set<Double> prochainesValeurs;
        boolean chocTrouve = false; // true si on a trouvé le choc de départ, false sinon
        while (!chocTrouve && (indiceChocDepart < finParcoursListe)) {
            prochainesValeurs = new HashSet<Double>(listeAccelX.subList(indiceChocDepart, indiceChocDepart+decalageAvant));
            if(prochainesValeurs.size() == 1) { // Toutes les valeurs sont identiques
                indiceChocDepart += decalageAvant;
            } else if(prochainesValeurs.size() >= 4) { // On a trouvé au moins 4 valeurs différentes
                accelCourante = listeAccelX.get(indiceChocDepart);
                for(int i = indiceChocDepart+1; i <= indiceChocDepart+decalageAvant; i++) {
                    if(accelCourante != listeAccelX.get(i)) {
                        chocTrouve = true;
                        break;
                    }
                    indiceChocDepart++;
                }
            } else { // On a trouvé seulement 2 ou 3 valeurs différentes
                indiceChocDepart++;
            }
        }
        
        // Le choc de départ n'a pas été trouvé, on a parcouru 0 metre
        if(!chocTrouve) {
            effacerTextViewDeplacement();
            return ERR_TROP_LENT;
        }
        
        // Le choc de départ a été trouvé, on cherche le choc de fin
        int indiceChocFin = listeAccelX.size()-decalageAvant;
        finParcoursListe = indiceChocDepart+3;
        chocTrouve = false; // true si on a trouvé le choc de fin, false sinon
        while (!chocTrouve && (indiceChocFin > finParcoursListe)) {
            prochainesValeurs = new HashSet<Double>(listeAccelX.subList(indiceChocFin-decalageAvant, indiceChocFin));
            if(prochainesValeurs.size() == 1) { // Toutes les valeurs sont identiques
                indiceChocFin -= decalageAvant;
            } else if(prochainesValeurs.size() >= 4) { // On a trouvé au moins 4 valeurs différentes
                
                accelCourante = listeAccelX.get(indiceChocFin);
                for(int i = indiceChocFin-1; i >= indiceChocFin-decalageAvant; i--) {
                    if(accelCourante != listeAccelX.get(i)) {
                        chocTrouve = true;
                        break;
                    }
                    indiceChocFin--;
                }
            } else { // On a trouvé seulement 2 ou 3 valeurs différentes
                indiceChocFin--;
            }
        }

        // Le choc de fin n'a pas été trouvé, on a parcouru 0 metre
        if(!chocTrouve) {
            effacerTextViewDeplacement();
            return ERR_TROP_LENT;
        }

        // Calcul de l'accélération (biais) de fin (On fait la fin avant le début pour des questions de taille de liste)
        double accelFin = 0.0;
        List<Double> bufferListe = listeAccelX.subList(indiceChocFin, listeAccelX.size()-1);
        for(int i = 0; i < bufferListe.size(); i++) {
            accelFin += bufferListe.get(i);
        }
        if (!bufferListe.isEmpty()) {
            accelFin = accelFin/bufferListe.size();
        }

        // Calcul de l'accélération (biais) de départ
        double accelDepart = 0.0;
        bufferListe = listeAccelX.subList(0, indiceChocDepart);
        for(int i = 0; i < bufferListe.size(); i++) {
            accelDepart += bufferListe.get(i);
        }
        if (!bufferListe.isEmpty()) {
            accelDepart = accelDepart/bufferListe.size();
        }
        
        // Elimination des accélérations et des "temps" en trop
        listeAccelX = new LinkedList<Double>(listeAccelX.subList(indiceChocDepart+1, indiceChocFin));
        listeTimes = new LinkedList<Long>(listeTimes.subList(indiceChocDepart+1, indiceChocFin+1)); // On rajoute +1 pour conserver une dernière valeur de temps t+1

        //Log.d("MILLI", "taille liste times corrig : "+listeTimes.size()+" - taille liste accel corrig : "+listeAccelX.size()+" - icd : "+indiceChocDepart+" - icf : "+indiceChocFin+"\naccel biais debut : "+accelDepart+" - accel biais fin : "+accelFin);
        //Log.d("MILLI", "listeTimes.getFirst() : "+listeTimes.getFirst()+" - listeTimes.getLast() : "+listeTimes.getLast()+" - listeAccelX.getFirst() : "+listeAccelX.getFirst()+" - listeAccelX.getLast() : "+listeAccelX.getLast());
        
        // Calcul du biais pour le recalibrage des accélérations
        // Initialement il s'agit de la moyenne des accélérations de début et de fin
        double biais = (accelDepart+accelFin)/2;
        ArrayList<Double> listeAccelBiaisee = new ArrayList<Double>();
        boolean recalibrage = true;
        while(recalibrage) { // Début du recalibrage itératif
            
            // Recalibrage des accélérations instantanées
            for(int i = 0; i < listeAccelX.size(); i++) {
                listeAccelBiaisee.add(listeAccelX.get(i)-biais);
            }
            recalibrage = false;
            
            // Calcul de la liste des vitesses instantanées (On intègre)
            double v0 = 0;
            for(int i = 0; i < listeAccelBiaisee.size()-1; i++) { // Le -1 n'est pas nécessaire si on utilise la méthode de Riemann pour l'intégration
                //v0 = integrationRiemann(listeAccelBiaisee.get(i), listeTimes.get(i), listeTimes.get(i+1), v0);
                v0 = integrationRomberg(listeAccelBiaisee.get(i), listeAccelBiaisee.get(i+1), listeTimes.get(i), listeTimes.get(i+1), v0);
                if (v0 > VITESSE_MIN) { // Vitesse OK
                    if (v0 < 0.01) { // On corrige les vitesses un peu négatives et/ou proches de 0 (i.e. RàZ de ces vitesses)
                        v0 = 0;
                    }
                    listeVitesses.add(v0);
                } else { // Vitesse trop négative (nécessitant une correction du biais)
                    
                    listeVitesses.clear(); // On vide la liste des vitesses
                    listeAccelBiaisee.clear(); // On vide la liste des accélérations biaisées
                    recalibrage = true; // On demande un nouveau calibrage
                    biais -= 0.02; // On change le biais
                    break;
                    
                }
            }
        } // Fin du recalibrage itératif
        
        
        // Calcul de la longueur du déplacement (On intègre)
        double longueurDeplacement = 0.0;
        for(int i = 0; i < listeVitesses.size()-1; i++) { // Le -1 n'est pas nécessaire si on utilise la méthode de Riemann pour l'intégration
            //longueurDeplacement = integrationRiemann(listeVitesses.get(i), listeTimes.get(i), listeTimes.get(i+1), longueurDeplacement);
            longueurDeplacement = integrationRomberg(listeVitesses.get(i), listeVitesses.get(i+1), listeTimes.get(i), listeTimes.get(i+1), longueurDeplacement);
        }
        
        if(longueurDeplacement < 0.01) {
            effacerTextViewDeplacement();
            return ERR_TROP_LENT;
        }
        
        afficherLongueurDeplacement(longueurDeplacement);
        return 0;
    }
    
    /**
     * Intègre selon la méthode de Riemann (méthode des rectangles).
     * @param a la valeur à intégrer sur l'intervalle
     * @param t1 la première mesure de temps
     * @param t2 la seconde mesure de temps
     * @param v0 la valeur initiale
     * @return l'intégrale selon la méthode des rectangles (soit : a * (t2 - t1)/1000000000 + v0)
     */
    private double integrationRiemann(double a, double t1, double t2, double v0) {
        return a * (t2 - t1)/1000000000.0 + v0;
    }
    
    /**
     * Intègre selon la méthode de Romberg (méthode des trapèzes).
     * @param a1 la première des valeurs à intégrer sur l'intervalle
     * @param a2 la dernière des valeurs à intégrer sur l'intervalle
     * @param t1 la première mesure de temps
     * @param t2 la seconde mesure de temps
     * @param v0 la valeur initiale
     * @return l'intégrale selon la méthode des trapèzes
     */
    private double integrationRomberg(double a1, double a2, double t1, double t2, double v0) {
        double deltaT = (t2 - t1)/1000000000.0;
        // On calcule d'abord l'aire du rectangle (comme dans la méthode de Riemann).
        double aireRectangle = a1 * deltaT + v0;
        // Puis on calcule l'aire du triangle qui, associé au rectangle, donne le trapèze.
        double aireTriangle = ((a2 - a1) * deltaT) / 2.0;
        double aireTrapeze = aireRectangle + aireTriangle;
        return aireTrapeze;
    }
    
    /**
     * Affiche la longueur du déplacement dans le TextView approprié.
     * @param longueurDeplacement la longueur (en mètres) du déplacement à afficher
     */
    private void afficherLongueurDeplacement(double longueurDeplacement) {
        String unite = "";
        String chaineDistance = "0.0";
        DecimalFormat f = new DecimalFormat();
        f.setMaximumFractionDigits(2);
        
        if(prefSystemeImperial) {
            // Système anglo-saxon
            longueurDeplacement = longueurDeplacement/0.3048;
            if(longueurDeplacement >= 1) {
                chaineDistance = f.format(longueurDeplacement);
                unite = " " + getString(R.string.unite_ft);
            } else if(longueurDeplacement >= 0.01) {
                chaineDistance =  f.format(longueurDeplacement*12);
                unite = " " + getString(R.string.unite_in);
            } else {
                unite = " " + getString(R.string.unite_ft);
            }
        } else {
            // Système métrique
            if(longueurDeplacement >= 1) {
                chaineDistance = f.format(longueurDeplacement);
                unite = " " + getString(R.string.unite_m);
            } else if(longueurDeplacement >= 0.01) {
                chaineDistance =  f.format(longueurDeplacement*100);
                unite = " " + getString(R.string.unite_cm);
            } else {
                unite = " " + getString(R.string.unite_m);
            }
        }
        
        messageHandler.sendMessage(Message.obtain(messageHandler, MSG_VALEUR_DISTANCE, chaineDistance+unite));
    }
    
    /**
     * Efface le contenu du TextView contenant la longueur du déplacement.
     */
    private void effacerTextViewDeplacement() {        
        messageHandler.sendMessage(Message.obtain(messageHandler, MSG_VALEUR_DISTANCE, ""));
    }
    
    /**
     * Méthode appelée à la destruction de l'activité.
     */
    @Override
    public void onDestroy() {
        if (wl.isHeld()) {
            wl.release(); // Fin du blocage de la mise en veille (En cas de fermeture brutale de l'activité)
        }
        super.onDestroy();
    }
}
