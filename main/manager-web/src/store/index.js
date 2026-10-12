import { goToPage } from "@/utils";
import Vue from 'vue';
import { readSession, writeSession, removeSession, isOperationsConsole } from '../security/session';
import Vuex from 'vuex';
import Api from '../apis/api';
import Constant from '../utils/constant';

Vue.use(Vuex)

export default new Vuex.Store({
  state: {
    token: '',
    userInfo: {}, // 添加用户信息存储
    pubConfig: { // 添加公共配置存储
      version: '',
      beianIcpNum: 'null',
      beianGaNum: 'null',
      allowUserRegister: false,
      sm2PublicKey: ''
    }
  },
  getters: {
    getToken(state) {
      if (!state.token) {
        state.token = readSession('token')
      }
      return state.token
    },
    getUserInfo(state) {
      return state.userInfo
    },
    getPubConfig(state) {
      return state.pubConfig
    }
  },
  mutations: {
    setToken(state, token) {
      state.token = token
      writeSession('token', token)
      if (!isOperationsConsole()) {
        localStorage.removeItem('currentWorkspaceId')
        localStorage.removeItem('currentWorkspaceCanWrite')
      }
    },
    setUserInfo(state, userInfo) {
      state.userInfo = userInfo
      writeSession('userInfo', JSON.stringify(userInfo))
    },
    setPubConfig(state, config) {
      state.pubConfig = config
      localStorage.setItem('pubConfig', JSON.stringify(config))
    },
    clearAuth(state) {
      state.token = ''
      state.userInfo = {}
      removeSession('token')
      removeSession('userInfo')
      if (!isOperationsConsole()) {
        localStorage.removeItem('currentWorkspaceId')
        localStorage.removeItem('currentWorkspaceCanWrite')
      }
    }
  },
  actions: {
    // 添加 logout action
    logout({ commit }) {
      return new Promise((resolve) => {
        const finish = () => {
          commit('clearAuth')
          goToPage(Constant.PAGE.LOGIN, true);
          resolve()
        }
        Api.user.logout(finish, finish)
      })
    },
    // 添加获取公共配置的 action
    fetchPubConfig({ commit }) {
      return new Promise((resolve) => {
        Api.user.getPubConfig(({ data }) => {
          if (data.code === 0) {
            commit('setPubConfig', data.data);
          }
          resolve();
        });
      });
    }
  },
  modules: {
  }
})
